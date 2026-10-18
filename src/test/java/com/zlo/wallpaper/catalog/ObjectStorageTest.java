package com.zlo.wallpaper.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlo.wallpaper.catalog.model.StoredObject;
import com.zlo.wallpaper.catalog.storage.CosStorage;
import com.zlo.wallpaper.catalog.storage.ObjectStorage;
import com.zlo.wallpaper.catalog.storage.OssStorage;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.OffsetDateTime;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证混合存储按每份文件路由，以及旧清理任务在切换新上传服务后仍删除正确的云对象。 */
class ObjectStorageTest {
    @Test
    void oldOssAndNewCosObjectsNeverUseTheActiveUploadSettingForReadsOrDeletes() throws Exception {
        ObjectStorage storage = new ObjectStorage(); OssStorage oss = mock(OssStorage.class); CosStorage cos = mock(CosStorage.class);
        ReflectionTestUtils.setField(storage,"oss",oss); ReflectionTestUtils.setField(storage,"cos",cos);
        ReflectionTestUtils.setField(storage,"provider","COS");
        OffsetDateTime expires = OffsetDateTime.now().plusMinutes(5);
        when(oss.createDownloadUrl("old","a","v1",expires)).thenReturn("oss-url");
        when(cos.createDownloadUrl("new","a",null,expires)).thenReturn("cos-url");
        assertEquals("oss-url",storage.createDownloadUrl("OSS","old","a","v1",expires));
        assertEquals("cos-url",storage.createDownloadUrl("COS","new","a",null,expires));
        StoredObject legacy = new ObjectMapper().readValue("{\"bucket\":\"old\",\"objectKey\":\"a\",\"versionId\":\"v1\"}",StoredObject.class);
        assertEquals("OSS",legacy.getProvider()); storage.deleteObject(legacy); verify(oss).deleteObject(legacy);
        StoredObject current = new StoredObject("COS","new","a",null); storage.deleteObject(current); verify(cos).deleteObject(current);
        verify(oss,never()).deleteObject(current); verify(cos,never()).deleteObject(legacy);
    }
}
