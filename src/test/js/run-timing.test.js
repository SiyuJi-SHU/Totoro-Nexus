const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const context=vm.createContext({});
vm.runInContext(fs.readFileSync(path.join(__dirname,'../../main/resources/static/platform-common.js'),'utf8')+'\nthis.timings=Platform.runTimings;',context);
const start='2026-09-22T10:00:00Z',at=Date.parse(start);
const e=(sequence,type,data,offset=0)=>({sequence,type,data,createdAt:new Date(at+offset).toISOString()});
test('replayed events are deduplicated and nested retrieval costs are not added into tools',()=>{
 const events=[e(1,'tool_start',{id:'knowledge.search',name:'知识检索'}),e(2,'usage',{kind:'embedding',purpose:'embedding',elapsedMs:100,outcome:'success'},100),e(3,'tool_end',{id:'knowledge.search',elapsedMs:500},500),e(4,'usage',{kind:'chat',purpose:'react-step',elapsedMs:2000,outcome:'success'},2500)];
 const data=context.timings([...events,...events],{status:'completed'},at+3000);
 assert.equal(data.rows.length,3);assert.equal(data.toolMs,500);assert.equal(data.llmMs,2000);
});
test('parallel same-model calls retain their own timing, first response and failure',()=>{
 const events=[e(1,'model_start',{callId:'a',purpose:'workflow-worker',model:'flash'}),e(2,'model_start',{callId:'b',purpose:'workflow-worker',model:'flash'}),e(3,'model_response',{callId:'a',firstResponseMs:120},120),e(4,'usage',{kind:'chat',purpose:'workflow-worker',elapsedMs:300,outcome:'success',timing:{callId:'a'}},300),e(5,'usage',{kind:'chat',purpose:'workflow-worker',elapsedMs:600,outcome:'failed',timing:{callId:'b',failureKind:'local_deadline'}},600)];
 const data=context.timings(events,{status:'completed'},at+700);
 assert.equal(data.rows.length,2);assert.equal(data.rows[0].firstResponseMs,120);assert.equal(data.rows[1].failureKind,'local_deadline');assert.equal(data.llmMs,900);
});
test('cancelled pending call stops its estimated clock at terminal time',()=>{
 const events=[e(1,'model_start',{callId:'a',purpose:'react-step'}),e(2,'terminal',{status:'cancelled'},1000)];
 const data=context.timings(events,{status:'cancelled'},at+9000);
 assert.equal(data.rows[0].elapsedMs,1000);assert.equal(data.rows[0].outcome,'cancelled');
});
test('live events received before history still match their original call',()=>{
 const events=[e(3,'usage',{kind:'chat',purpose:'react-step',elapsedMs:600,outcome:'success',timing:{callId:'a'}},600),e(1,'model_start',{callId:'a',purpose:'react-step'}),e(2,'model_response',{callId:'a',firstResponseMs:120},120)];
 const data=context.timings(events,{status:'completed'},at+1000);
 assert.equal(data.rows.length,1);assert.equal(data.llmMs,600);assert.equal(data.rows[0].firstResponseMs,120);
});
