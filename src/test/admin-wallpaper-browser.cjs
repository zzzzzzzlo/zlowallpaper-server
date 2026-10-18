const assert = require('node:assert/strict');
const { chromium } = require('D:/Codex/software/zlowallpaper/admin/node_modules/playwright');
const { pathToFileURL } = require('node:url');
(async () => {
    const { createStaticServer } = await import(pathToFileURL('D:/Codex/software/zlowallpaper/admin/server.mjs').href);
    const server = createStaticServer();
    await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(14173,'127.0.0.1',resolve);});
    let browser;
    try {
        browser = await chromium.launch({channel:'msedge',headless:true});
        const page = await browser.newPage({viewport:{width:1440,height:1000}});
        await page.addInitScript(base => localStorage.setItem('zlo.admin.preferences',JSON.stringify({mode:'real',base})),process.env.TEST_API_BASE);
        const writes=[], errors=[];
        page.on('pageerror',error=>errors.push(error.message));
        page.on('request',request=>{
            if(request.url().startsWith(process.env.TEST_API_BASE) && ['POST','PATCH','PUT'].includes(request.method()) && !request.url().includes('/auth/'))
                writes.push({method:request.method(),path:new URL(request.url()).pathname,type:request.headers()['content-type']});
        });
        await page.goto('http://127.0.0.1:14173');
        await page.locator('#username').fill('wallpaper_admin');await page.locator('#password').fill('WallpaperTest123!');
        await page.locator('#login-submit').click();await page.locator('#workspace').waitFor({state:'visible'});
        await page.locator('[data-create]').first().click();await page.locator('#editor-dialog').waitFor({state:'visible'});
        const png=Buffer.from('89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000b49444154789c636000020000050001a5f645400000000049454e44ae426082','hex');
        await page.locator('#product-title').fill('浏览器真实接口测试');await page.locator('#product-category').selectOption('1');
        await page.locator('#product-price').fill('0.29');
        await page.locator('#cover-file').setInputFiles({name:'cover.png',mimeType:'image/png',buffer:png});
        await page.locator('#original-file').setInputFiles({name:'original.png',mimeType:'image/png',buffer:png});
        assert.equal(writes.length,0,'本地选择不应发保存/上传请求');
        const createResponse=page.waitForResponse(r=>r.request().method()==='POST' && r.url().endsWith('/admin/wallpapers'));
        await page.locator('#save-product').click();assert.equal((await createResponse).status(),201);
        await page.locator('#editor-dialog').waitFor({state:'hidden'});
        await page.getByRole('button',{name:'编辑 浏览器真实接口测试',exact:true}).waitFor();
        assert.equal(writes.length,1);assert.match(writes[0].type,/multipart\/form-data/);
        await page.waitForFunction(()=>[...document.querySelectorAll('.cover-thumb')].some(image=>image.tagName==='IMG' && image.complete && image.naturalWidth>0));
        await page.getByRole('button',{name:'上架 浏览器真实接口测试',exact:true}).click();
        await page.locator('#confirm-action').click();await page.locator('#confirm-dialog').waitFor({state:'hidden'});
        await page.getByRole('button',{name:'编辑 浏览器真实接口测试',exact:true}).click();await page.locator('#editor-dialog').waitFor({state:'visible'});
        await page.locator('#product-title').fill('已修改商品');
        const patchResponse=page.waitForResponse(r=>r.request().method()==='PATCH' && /\/admin\/wallpapers\/\d+$/.test(new URL(r.url()).pathname));
        await page.locator('#save-product').click();assert.equal((await patchResponse).status(),200);
        await page.locator('#editor-dialog').waitFor({state:'hidden'});await page.getByRole('button',{name:'编辑 已修改商品',exact:true}).waitFor();
        await page.getByRole('button',{name:'编辑 已修改商品',exact:true}).click();await page.locator('#editor-dialog').waitFor({state:'visible'});
        const count=writes.length;
        await page.locator('#original-file').setInputFiles({name:'cancelled.png',mimeType:'image/png',buffer:png});
        page.once('dialog',dialog=>dialog.accept());await page.locator('#cancel-editor').click();await page.locator('#editor-dialog').waitFor({state:'hidden'});
        assert.equal(writes.length,count,'取消不能提交选中的文件');
        await page.getByRole('button',{name:'下架 已修改商品',exact:true}).click();await page.locator('#confirm-action').click();await page.locator('#confirm-dialog').waitFor({state:'hidden'});
        await page.getByRole('button',{name:'上架 已修改商品',exact:true}).waitFor();
        assert.equal(writes.some(request=>request.path.includes('/admin/uploads')),false);assert.deepEqual(errors,[]);
        await page.screenshot({path:'E:/zlowallpaper_sever/target/admin-wallpaper-browser.png',fullPage:true});
        console.log('PASS: 浏览器→真实Tomcat/H2：登录、草稿创建、管理员封面、上架、multipart PATCH、取消无写入、下架；云存储使用测试替身。');
    } finally { if(browser) await browser.close(); await new Promise(resolve=>{server.close(resolve);server.closeAllConnections();}); }
})().catch(error=>{console.error(error.message);process.exitCode=1;});
