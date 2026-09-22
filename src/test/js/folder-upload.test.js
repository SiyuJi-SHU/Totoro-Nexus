const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const upload=require('../../main/resources/static/folder-upload.js');

const file=(name,size=10,relative='')=>({name,size,webkitRelativePath:relative});

test('folder selection keeps supported files and their relative folders',()=>{
  const result=upload.classify([
    file('readme.md',12,'handbook/readme.md'),
    file('notes.TXT',8,'handbook/team/notes.TXT'),
    file('diagram.png',20,'handbook/diagram.png')
  ]);
  assert.deepEqual(result.accepted.map(x=>({name:x.name,folder:x.folder,path:x.path})),[
    {name:'readme.md',folder:'handbook',path:'handbook/readme.md'},
    {name:'notes.TXT',folder:'handbook/team',path:'handbook/team/notes.TXT'}
  ]);
  assert.equal(result.skipped.length,1);
  assert.equal(result.invalid.length,0);
});

test('folder selection rejects unsafe empty and oversized supported files',()=>{
  const result=upload.classify([
    file('empty.md',0,'docs/empty.md'),
    file('large.txt',upload.MAX_BYTES+1,'docs/large.txt'),
    file('hidden.md',10,'docs/.private/hidden.md'),
    file('escape.md',10,'docs/../escape.md')
  ]);
  assert.equal(result.accepted.length,0);
  assert.equal(result.invalid.length,4);
});

test('batch summary separates indexing actions failures and skipped files',()=>{
  const selection=upload.classify([
    file('a.md',10,'docs/a.md'),
    file('b.txt',10,'docs/b.txt'),
    file('c.pdf',10,'docs/c.pdf')
  ]);
  const summary=upload.createSummary(selection);
  upload.record(summary,selection.accepted[0],{action:'indexed'});
  upload.record(summary,selection.accepted[1],null,new Error('索引不可用'));
  assert.equal(upload.completed(summary),1);
  assert.equal(summary.failed,1);
  assert.equal(summary.skipped,1);
  assert.equal(upload.summaryText(summary),'完成 1 个，失败 1 个，跳过 1 个');
});

test('console exposes separate file and recursive folder inputs',()=>{
  const staticDir=path.join(__dirname,'../../main/resources/static');
  const html=fs.readFileSync(path.join(staticDir,'console.html'),'utf8');
  const consoleSource=fs.readFileSync(path.join(staticDir,'platform-console.js'),'utf8');
  assert.match(html,/<script src="\/folder-upload\.js"><\/script><script src="\/platform-console\.js(?:\?[^"\s]*)?">/);
  assert.match(consoleSource,/class="kb-folder-upload"[^>]*webkitdirectory directory multiple/);
  assert.match(consoleSource,/data\.append\('folder',item\.folder\)/);
  assert.match(consoleSource,/FolderUpload\.classify\(files\)/);
});
