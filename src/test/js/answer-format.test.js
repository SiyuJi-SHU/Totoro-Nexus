const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const path=require('node:path');
const context=vm.createContext({});
vm.runInContext(fs.readFileSync(path.join(__dirname,'../../main/resources/static/platform-common.js'),'utf8')+'\nthis.format=Platform.inlineMarkdown;',context);

test('answer emphasis renders while filenames and code remain literal',()=>{
  assert.equal(context.format('知识库 **Gitlab运维知识库**，共 **35 份文档**'), '知识库 <strong>Gitlab运维知识库</strong>，共 <strong>35 份文档</strong>');
  assert.equal(context.format('`**literal**` gitlab__docs__alerts.md'), '<code>**literal**</code> gitlab__docs__alerts.md');
  assert.equal(context.format('未完成 **加粗'), '未完成 **加粗');
});

test('model supplied HTML cannot become executable markup',()=>{
  assert.equal(context.format('**<img src=x onerror=alert(1)>**'), '<strong>&lt;img src=x onerror=alert(1)&gt;</strong>');
  assert.equal(context.format('`<script>alert(1)</script>`'), '<code>&lt;script&gt;alert(1)&lt;/script&gt;</code>');
  assert.equal(context.format('&lt;script&gt;'), '&amp;lt;script&amp;gt;');
});

test('headings and web links are readable without allowing executable links',()=>{
  assert.equal(context.format('### 结论\n[来源](https://example.com/?a=1&b=2)'), '<h3>结论</h3>\n<a href="https://example.com/?a=1&amp;b=2" target="_blank" rel="noopener noreferrer">来源</a>');
  assert.equal(context.format('[点击](javascript:alert(1))'), '[点击](javascript:alert(1))');
  assert.equal(context.format('`### 原样`'), '<code>### 原样</code>');
  assert.ok(!context.format('[<img>](https://example.com/"onclick="x)').includes('"onclick='));
});
