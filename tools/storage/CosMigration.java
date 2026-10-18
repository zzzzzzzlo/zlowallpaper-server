import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.common.comm.SignVersion;
import com.aliyun.oss.model.GetObjectRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlo.wallpaper.catalog.model.StoredObject;
import com.zlo.wallpaper.catalog.storage.CosStorage;
import com.zlo.wallpaper.config.CosProperties;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/**
 * 本机一次性迁移命令，由Run-CosMigration.ps1编译并调用，不注册为Spring Bean或HTTP接口。
 * plan只读取数据库/本地文件；stage上传并下载校验COS；apply只在所有对象校验后短事务切换位置。
 * 保留全部旧OSS对象，rollback可以恢复原位置；用户、订单、权益和商品业务字段不参与UPDATE。
 */
public class CosMigration {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path ROOT = Path.of("E:/zlowallpaper_sever");
    private static final Path WORK = ROOT.resolve(".local-setup/cos-migration");
    private static final List<String> OTHER_TABLES = List.of("users","sessions","categories","orders","entitlements");

    /** 本地清单的一份文件，不是数据库新实体；JSON供命令断点续传、审计和回退。 */
    public static class FileItem {
        public long wallpaperId;
        public String purpose, provider, bucket, key, versionId, filename, mimeType, sha256;
        public long sizeBytes;
        public String localPath, destinationKey, cosBucket, cosVersionId;
        public boolean verified;
    }

    /** 持久迁移清单；同一清单中的目标UUID固定，重复执行stage不会重复上传已校验文件。 */
    public static class Plan {
        public String createdAt, targetBucket;
        public List<FileItem> files = new ArrayList<>();
    }

    /** PowerShell调用入口，参数为plan/stage/apply/verify/rollback；失败只输出安全摘要。 */
    public static void main(String[] args) {
        try {
            if (args.length != 1) { throw new IllegalArgumentException("Specify plan, stage, apply, verify or rollback"); }
            Files.createDirectories(WORK);
            Properties settings = settings();
            try (Connection db = connection(settings)) {
                switch (args[0]) {
                    case "plan" -> makePlan(db);
                    case "stage" -> stage(loadPlan(),settings);
                    case "apply" -> apply(loadPlan(),db,false);
                    case "verify" -> verify(loadPlan(),db);
                    case "rollback" -> apply(loadPlan(),db,true);
                    default -> throw new IllegalArgumentException("Unknown migration mode");
                }
            }
        } catch (Exception exception) {
            // 不打印SDK原始异常、请求签名、密码或环境配置；自定义状态帮助定位下一步。
            if (exception instanceof com.zlo.wallpaper.common.BusinessException business) {
                System.err.println("Migration failed: "+business.getCode()+" "+business.getMessage());
            } else if (exception instanceof IllegalStateException || exception instanceof IllegalArgumentException) {
                System.err.println("Migration failed: "+exception.getMessage());
            } else { System.err.println("Migration failed: "+exception.getClass().getSimpleName()+"; no source object was deleted."); }
            System.exit(1);
        }
    }

    /** main调用；仅从本机.env读取配置，不输出配置值。 */
    private static Properties settings() throws Exception {
        Properties settings = new Properties();
        try (java.io.Reader input = Files.newBufferedReader(ROOT.resolve(".env"),StandardCharsets.UTF_8)) { settings.load(input); }
        return settings;
    }

    /** main调用；连接既有本机库，用于元数据读取或明确的存储位置UPDATE。 */
    private static Connection connection(Properties settings) throws Exception {
        DriverManager.setLoginTimeout(5);
        return DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/zlowallpaper?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
                "root",settings.getProperty("MYSQL_ROOT_PASSWORD"));
    }

    /** plan入口：生成固定目标键，按大小和SHA匹配E盘已有文件，不读取任何云对象。 */
    private static void makePlan(Connection db) throws Exception {
        if (Files.exists(WORK.resolve("plan.json"))) { throw new IllegalStateException("plan.json already exists; reuse stage/apply or archive this completed plan before making a new one"); }
        Plan plan = new Plan(); plan.createdAt = Instant.now().toString();
        try (Statement statement = db.createStatement(); ResultSet rows = statement.executeQuery("SELECT * FROM wallpapers ORDER BY id")) {
            while (rows.next()) {
                for (String purpose : List.of("original","cover")) {
                    String key = rows.getString(purpose+"_object_key");
                    if (key == null) { continue; }
                    String provider = rows.getString(purpose+"_storage_provider");
                    if ("COS".equals(provider)) { continue; }
                    if (!"OSS".equals(provider) && !"LOCAL".equals(provider)) { throw new IllegalStateException("Unsupported provider on product "+rows.getLong("id")); }
                    FileItem file = new FileItem(); file.wallpaperId = rows.getLong("id"); file.purpose = purpose;
                    file.provider = provider; file.bucket = rows.getString(purpose+"_bucket"); file.key = key;
                    file.versionId = rows.getString(purpose+"_object_version_id"); file.filename = rows.getString(purpose+"_filename");
                    file.mimeType = rows.getString(purpose+"_mime_type"); file.sizeBytes = rows.getLong(purpose+"_size_bytes");
                    file.sha256 = rows.getString(purpose+"_sha256");
                    if(file.sha256 == null || !file.sha256.matches("[a-f0-9]{64}") || file.sizeBytes <= 0) { throw new IllegalStateException("File metadata incomplete on product "+file.wallpaperId); }
                    String extension = rows.getString(purpose+"_file_extension");
                    if(extension == null || !extension.matches("jpg|jpeg|png|bmp|webp|gif|mp4|webm|mov|avi")) { throw new IllegalStateException("Invalid extension on product "+file.wallpaperId); }
                    file.destinationKey = "zlowallpaper/"+("cover".equals(purpose)?"covers/":"originals/")+UUID.randomUUID()+"."+extension;
                    plan.files.add(file);
                }
            }
        }
        Set<Long> sizes = new HashSet<>(); for(FileItem file:plan.files) { sizes.add(file.sizeBytes); }
        Map<String,String> local = new HashMap<>();
        for(Path directory:List.of(Path.of("E:/壁纸"),Path.of("E:/zlowallpaper/loaded_wallpaper"))) {
            if(!Files.isDirectory(directory)) { continue; }
            try(Stream<Path> paths = Files.walk(directory)) {
                for(Path path:paths.filter(Files::isRegularFile).toList()) {
                    if(sizes.contains(Files.size(path))) { local.putIfAbsent(sha256(path),path.toAbsolutePath().toString()); }
                }
            }
        }
        long total = 0, missing = 0; int found = 0;
        for(FileItem file:plan.files) {
            file.localPath = local.get(file.sha256); total += file.sizeBytes;
            if(file.localPath != null) { found++; } else { missing += file.sizeBytes; }
        }
        writePlan(plan);
        Map<String,Object> summary = new LinkedHashMap<>(); summary.put("files",plan.files.size()); summary.put("totalBytes",total);
        summary.put("localShaMatches",found); summary.put("sourceCloudBytesIfMissing",missing); summary.put("cloudRequests",0);
        writeJson(WORK.resolve("plan-summary.json"),summary); System.out.println(JSON.writeValueAsString(summary));
    }

    /** stage入口：获取源文件、上传COS、从COS实际下载逐份校验后标记verified，不修改数据库。 */
    private static void stage(Plan plan, Properties settings) throws Exception {
        CosProperties properties = new CosProperties(); properties.setBucket(required(settings,"COS_BUCKET"));
        properties.setRegion(required(settings,"COS_REGION")); properties.setSecretId(required(settings,"COS_SECRET_ID"));
        properties.setSecretKey(required(settings,"COS_SECRET_KEY"));
        if(plan.targetBucket != null && !plan.targetBucket.equals(properties.getBucket())) { throw new IllegalStateException("Target bucket differs from existing migration plan"); }
        plan.targetBucket = properties.getBucket(); writePlan(plan);
        CosStorage cos = new CosStorage(properties); OSS oss = null;
        try {
            int index = 0;
            for(FileItem file:plan.files) {
                index++;
                if(file.verified) { System.out.println("Verified item reused: "+index+"/"+plan.files.size()); continue; }
                Path source = file.localPath == null ? WORK.resolve("payload/"+file.sha256) : Path.of(file.localPath);
                if(!matches(source,file)) {
                    Files.createDirectories(WORK.resolve("payload")); source = WORK.resolve("payload/"+file.sha256);
                    if(!matches(source,file)) {
                        if("LOCAL".equals(file.provider)) {
                            Path storageRoot = Path.of(settings.getProperty("WALLPAPER_STORAGE_ROOT","D:/Codex/software/zlowallpaper-storage")).toAbsolutePath().normalize();
                            Path legacy = storageRoot.resolve(file.key).normalize();
                            if(!legacy.startsWith(storageRoot) || !Files.isRegularFile(legacy) || !legacy.toRealPath().startsWith(storageRoot.toRealPath())) { throw new IllegalStateException("Local source missing for product "+file.wallpaperId); }
                            Files.copy(legacy,source,StandardCopyOption.REPLACE_EXISTING);
                        } else {
                            if(oss == null) { oss = createOss(settings); }
                            GetObjectRequest request = new GetObjectRequest(file.bucket,file.key);
                            if(file.versionId != null && !file.versionId.isBlank()) { request.setVersionId(file.versionId); }
                            oss.getObject(request,source.toFile());
                        }
                    }
                }
                if(!matches(source,file)) { throw new IllegalStateException("Source SHA/size mismatch for product "+file.wallpaperId+" "+file.purpose); }
                // 上传结果已记录但验证未完成时直接重试验证，不重放上传、也不覆盖远端UUID对象。
                if(file.cosBucket == null) {
                    StoredObject target = cos.upload(source,file.destinationKey,file.mimeType,file.sizeBytes);
                    file.cosBucket = target.getBucket(); file.cosVersionId = target.getVersionId(); writePlan(plan);
                }
                Path downloaded = WORK.resolve("verify-"+file.wallpaperId+"-"+file.purpose+".tmp");
                cos.downloadToFile(file.cosBucket,file.destinationKey,file.cosVersionId,downloaded);
                try {
                    if(!matches(downloaded,file)) { throw new IllegalStateException("COS SHA/size mismatch for product "+file.wallpaperId+" "+file.purpose); }
                    file.verified = true; writePlan(plan);
                } finally { Files.deleteIfExists(downloaded); }
                System.out.println("COS item verified: "+index+"/"+plan.files.size()+", product="+file.wallpaperId+", kind="+file.purpose+", bytes="+file.sizeBytes);
            }
        } finally { cos.close(); if(oss != null) { oss.shutdown(); } }
        System.out.println("All COS objects verified; database locations still unchanged.");
    }

    /** stage仅在没有本地SHA匹配文件时调用；按既有OSS配置创建只用于读取的客户端。 */
    private static OSS createOss(Properties settings) {
        String endpoint = required(settings,"OSS_ENDPOINT"); if(!endpoint.contains("://")) { endpoint = "https://"+endpoint; }
        ClientBuilderConfiguration config = new ClientBuilderConfiguration(); config.setSignatureVersion(SignVersion.V4);
        config.setConnectionTimeout(5000); config.setSocketTimeout(60000); config.setMaxErrorRetry(0);
        return OSSClientBuilder.create().endpoint(endpoint).region(required(settings,"OSS_REGION"))
                .credentialsProvider(new DefaultCredentialProvider(required(settings,"OSS_ACCESS_KEY_ID"),required(settings,"OSS_ACCESS_KEY_SECRET")))
                .clientConfiguration(config).build();
    }

    /** apply/rollback入口；调用前停止后端写入，锁住商品、复查文件快照，一次事务修改八个位置字段。 */
    private static void apply(Plan plan, Connection db, boolean rollback) throws Exception {
        for(FileItem file:plan.files) { if(!file.verified || file.cosBucket == null) { throw new IllegalStateException("All remote objects must be verified before changing database locations"); } }
        Map<String,String> otherBefore = digests(db,OTHER_TABLES,false);
        String businessBefore = digest(db,"wallpapers",true);
        db.setAutoCommit(false);
        try {
            List<Map<String,String>> backup = new ArrayList<>(); Map<Long,Map<String,String>> rows = new HashMap<>();
            try(Statement query = db.createStatement(); ResultSet result = query.executeQuery("SELECT * FROM wallpapers ORDER BY id FOR UPDATE")) {
                ResultSetMetaData meta = result.getMetaData();
                while(result.next()) {
                    Map<String,String> row = new LinkedHashMap<>();
                    for(int column=1;column<=meta.getColumnCount();column++) { row.put(meta.getColumnName(column),result.getString(column)); }
                    rows.put(result.getLong("id"),row); backup.add(row);
                }
            }
            Set<String> planned = new HashSet<>();
            for(FileItem file:plan.files) {
                String prefix = prefix(file); Map<String,String> row = rows.get(file.wallpaperId);
                if(row == null || !Objects.equals(row.get(prefix+"sha256"),file.sha256)
                        || !Long.toString(file.sizeBytes).equals(row.get(prefix+"size_bytes"))) { throw new IllegalStateException("Product file content changed since plan: "+file.wallpaperId); }
                String provider = rollback?"COS":file.provider, bucket = rollback?file.cosBucket:file.bucket;
                String key = rollback?file.destinationKey:file.key, version = rollback?file.cosVersionId:file.versionId;
                if(!Objects.equals(row.get(prefix+"storage_provider"),provider) || !Objects.equals(row.get(prefix+"bucket"),bucket)
                        || !Objects.equals(row.get(prefix+"object_key"),key) || !Objects.equals(row.get(prefix+"object_version_id"),version)) {
                    throw new IllegalStateException("Product location changed since plan: "+file.wallpaperId+" "+file.purpose);
                }
                planned.add(file.wallpaperId+":"+file.purpose);
            }
            if(!rollback) {
                for(Map.Entry<Long,Map<String,String>> entry:rows.entrySet()) {
                    for(String purpose:List.of("original","cover")) {
                        Map<String,String> row = entry.getValue(); String provider = row.get(purpose+"_storage_provider");
                        if(row.get(purpose+"_object_key") != null && !"COS".equals(provider) && !planned.contains(entry.getKey()+":"+purpose)) {
                            throw new IllegalStateException("Unplanned source file exists; regenerate migration plan before switch");
                        }
                    }
                }
                writeJson(WORK.resolve("wallpapers-before.json"),backup);
            }
            for(FileItem file:plan.files) {
                String prefix = prefix(file);
                // 列名前缀仅来自固定original/cover枚举；值全部用PreparedStatement绑定。
                try(PreparedStatement update = db.prepareStatement("UPDATE wallpapers SET "+prefix+"storage_provider=?, "+prefix+"bucket=?, "+prefix+"object_key=?, "+prefix+"object_version_id=? WHERE id=?")) {
                    update.setString(1,rollback?file.provider:"COS"); update.setString(2,rollback?file.bucket:file.cosBucket);
                    update.setString(3,rollback?file.key:file.destinationKey); update.setString(4,rollback?file.versionId:file.cosVersionId);
                    update.setLong(5,file.wallpaperId); if(update.executeUpdate()!=1) { throw new IllegalStateException("Product update did not affect exactly one row"); }
                }
            }
            if(!businessBefore.equals(digest(db,"wallpapers",true))) { throw new IllegalStateException("Product business fields changed during location update"); }
            if(!otherBefore.equals(digests(db,OTHER_TABLES,false))) { throw new IllegalStateException("Non-file tables changed during location update"); }
            db.commit(); db.setAutoCommit(true);
        } catch(Exception exception) { db.rollback(); db.setAutoCommit(true); throw exception; }
        Map<String,Object> result = new LinkedHashMap<>(); result.put("mode",rollback?"rollback":"apply"); result.put("files",plan.files.size());
        result.put("otherTablesUnchanged",otherBefore.equals(digests(db,OTHER_TABLES,false)));
        result.put("productBusinessFieldsUnchanged",businessBefore.equals(digest(db,"wallpapers",true)));
        result.put("sourceObjectsDeleted",0); result.put("completedAt",Instant.now().toString());
        writeJson(WORK.resolve(rollback?"rollback-result.json":"apply-result.json"),result);
        System.out.println(JSON.writeValueAsString(result));
    }

    /** verify入口：只核对已提交位置和业务摘要，不重复下载所有文件。远端内容校验记录来自stage。 */
    private static void verify(Plan plan, Connection db) throws Exception {
        for(FileItem file:plan.files) {
            String prefix = prefix(file);
            try(PreparedStatement query = db.prepareStatement("SELECT "+prefix+"storage_provider, "+prefix+"bucket, "+prefix+"object_key, "+prefix+"object_version_id, "+prefix+"sha256, "+prefix+"size_bytes FROM wallpapers WHERE id=?")) {
                query.setLong(1,file.wallpaperId);
                try(ResultSet row = query.executeQuery()) {
                    if(!row.next() || !"COS".equals(row.getString(1)) || !Objects.equals(file.cosBucket,row.getString(2))
                            || !Objects.equals(file.destinationKey,row.getString(3)) || !Objects.equals(file.cosVersionId,row.getString(4))
                            || !file.sha256.equals(row.getString(5)) || file.sizeBytes!=row.getLong(6)) { throw new IllegalStateException("Migrated metadata mismatch: "+file.wallpaperId+" "+file.purpose); }
                }
            }
        }
        System.out.println("All "+plan.files.size()+" migrated file locations and content hashes match the verified plan.");
    }

    private static String prefix(FileItem file) {
        if(!List.of("original","cover").contains(file.purpose)) { throw new IllegalStateException("Invalid file purpose in migration plan"); }
        return file.purpose+"_";
    }

    /** apply调用；摘要只保存哈希，不把其他用户密码、余额或令牌复制进日志。 */
    private static Map<String,String> digests(Connection db, List<String> tables, boolean productBusinessOnly) throws Exception {
        Map<String,String> result = new LinkedHashMap<>();
        for(String table:tables) { result.put(table,digest(db,table,productBusinessOnly)); } return result;
    }

    private static String digest(Connection db, String table, boolean productBusinessOnly) throws Exception {
        if(!List.of("users","sessions","categories","orders","entitlements","wallpapers").contains(table)) { throw new IllegalStateException("Invalid table"); }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try(Statement query=db.createStatement();ResultSet rows=query.executeQuery("SELECT * FROM "+table+" ORDER BY id")) {
            ResultSetMetaData meta=rows.getMetaData();
            while(rows.next()) {
                Map<String,String> row = new LinkedHashMap<>();
                for(int column=1;column<=meta.getColumnCount();column++) {
                    String name=meta.getColumnName(column);
                    if(productBusinessOnly && name.matches("(original|cover)_(storage_provider|bucket|object_key|object_version_id)")) {continue;}
                    row.put(name,rows.getString(column));
                }
                digest.update(JSON.writeValueAsBytes(row));digest.update((byte)'\n');
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static boolean matches(Path path, FileItem file) throws Exception {
        return Files.isRegularFile(path) && Files.size(path)==file.sizeBytes && file.sha256.equals(sha256(path));
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream input=Files.newInputStream(path)) { byte[] buffer=new byte[65536];int read;while((read=input.read(buffer))!=-1) {digest.update(buffer,0,read);} }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String required(Properties properties, String name) {
        String value=properties.getProperty(name);if(value==null || value.isBlank()) {throw new IllegalStateException("Missing local setting: "+name);}return value.strip();
    }

    private static Plan loadPlan() throws Exception { return JSON.readValue(WORK.resolve("plan.json").toFile(),Plan.class); }
    private static void writePlan(Plan plan) throws Exception { writeJson(WORK.resolve("plan.json"),plan); }

    /** 清单/结果通过同目录临时文件替换写入，避免中断后读取到半份JSON。 */
    private static void writeJson(Path path, Object value) throws Exception {
        Path temporary=path.resolveSibling(path.getFileName()+".tmp");
        JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(),value);
        try {Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
        catch(AtomicMoveNotSupportedException exception) {Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING);}
    }
}
