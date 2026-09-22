const Platform = (() => {
  let csrf;
  const $ = s => document.querySelector(s);
  const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  // Only create our own tags; model/user HTML remains escaped, including inside code.
  function inlineMarkdown(value) {
    const text=String(value??'');let html='',end=0;
    for(const match of text.matchAll(/(`+)([^\n]*?)\1|\*\*(?=\S)([^\n]*?\S)\*\*|\[([^\]\n]+)\]\((https?:\/\/[^\s<>]+?)\)/g)) {
      html+=esc(text.slice(end,match.index));
      html+=match[1]?`<code>${esc(match[2])}</code>`:match[4]?`<a href="${esc(match[5])}" target="_blank" rel="noopener noreferrer">${esc(match[4])}</a>`:`<strong>${esc(match[3])}</strong>`;
      end=match.index+match[0].length;
    }
    return (html+esc(text.slice(end))).replace(/^#{1,6} +(.+)$/gm,'<h3>$1</h3>');
  }
  // Events are replayable. LLM and tool durations overlap (retrieval calls models,
  // Workflow runs directions concurrently), so never add them to obtain wall time.
  function runTimings(events,run={},now=Date.now()) {
    const rows=[],models=new Map(),tools=new Map();
    const labels={'task-routing':'意图判断','react-step':'决策 / 作答','answer-synthesis':'整理答案','direct-response':'直接回答','plan-create':'制定计划','plan-execute':'执行计划','plan-observe':'检查计划','workflow-supervisor':'选择调查方向','workflow-worker':'分析调查方向','query-rewrite':'检索词改写',embedding:'向量化',rerank:'精排'};
    const seen=new Set();let terminal=null;
    // History loading and live SSE can arrive in either order after reconnect.
    for(const event of [...events].sort((a,b)=>(a.sequence??0)-(b.sequence??0))){
      if(event.sequence!=null&&seen.has(event.sequence))continue;if(event.sequence!=null)seen.add(event.sequence);
      const d=event.data||{},at=Date.parse(d.startedAt||event.createdAt)||now;
      if(event.type==='terminal'){terminal={at,status:d.status};continue;}
      if(event.type==='model_start'){
        const row={kind:'chat',label:'LLM · '+(labels[d.purpose]||d.purpose),model:d.model,direction:d.direction,start:at,purpose:d.purpose,callId:d.callId,outcome:'running'};
        rows.push(row);models.set(d.callId,row);
      }else if(event.type==='model_response'){
        const row=models.get(d.callId);if(row)row.firstResponseMs=d.firstResponseMs;
      }else if(event.type==='usage'){
        const row=models.get(d.timing?.callId)||{kind:d.kind,label:(d.kind==='chat'?'LLM · ':'检索子项 · ')+(labels[d.purpose]||d.purpose),model:d.model,start:at-(d.elapsedMs||0)};
        if(!rows.includes(row))rows.push(row);
        Object.assign(row,{elapsedMs:d.elapsedMs,outcome:d.outcome,firstResponseMs:d.timing?.firstResponseMs??row.firstResponseMs,failureKind:d.timing?.failureKind,httpStatus:d.timing?.httpStatus});
      }else if(event.type==='tool_start'){
        const key=d.id+'|'+(d.direction||''),row={kind:'tool',label:'工具 · '+(d.name||d.id),direction:d.direction,start:at,outcome:'running'};
        rows.push(row);if(!tools.has(key))tools.set(key,[]);tools.get(key).push(row);
      }else if(event.type==='tool_end'||event.type==='tool_error'){
        const key=(d.id||d.tool)+'|'+(d.direction||''),row=tools.get(key)?.shift()||{kind:'tool',label:'工具 · '+(d.id||d.tool),start:at-(d.elapsedMs||0)};
        if(!rows.includes(row))rows.push(row);
        Object.assign(row,{elapsedMs:d.elapsedMs??Math.max(0,at-row.start),outcome:event.type==='tool_error'?'failed':'success'});
      }
    }
    const finished=Date.parse(run.finishedAt)||terminal?.at;
    const isActive=['queued','running','reviewing'].includes(run.status);
    for(const row of rows)if(row.outcome==='running'){
      row.elapsedMs=Math.max(0,(isActive?now:finished||now)-row.start);
      row.estimated=true;if(!isActive)row.outcome=run.status==='cancelled'?'cancelled':run.status==='timed_out'?'timed_out':'interrupted';
    }
    rows.sort((a,b)=>a.start-b.start);
    return {rows,llmMs:rows.filter(r=>r.kind==='chat').reduce((n,r)=>n+(r.elapsedMs||0),0),toolMs:rows.filter(r=>r.kind==='tool').reduce((n,r)=>n+(r.elapsedMs||0),0)};
  }
  async function api(path, options = {}) {
    const method = options.method || 'GET';
    const headers = {...options.headers};
    if (!['GET','HEAD'].includes(method)) {
      if (!csrf) csrf = await fetch('/api/auth/csrf').then(r=>r.json());
      headers[csrf.headerName] = csrf.token;
    }
    let body=options.body;
    if (body && !(body instanceof FormData) && !(body instanceof URLSearchParams)) {headers['Content-Type']='application/json';body=JSON.stringify(body);}
    const r=await fetch(path,{...options,method,headers,body});
    const data=r.status===204?null:await r.json().catch(()=>({message:`请求失败 (${r.status})`}));
    if(!r.ok){if(r.status===401&&!path.includes('/auth/login'))location.href='/login.html';throw new Error(data?.message||`请求失败 (${r.status})`);}
    return data;
  }
  async function auth(admin=false) {
    const me=await api('/api/auth/me');
    if(!me.authenticated){location.href='/login.html';throw new Error('请先登录');}
    if(admin&&me.role!=='ADMIN'){location.href='/';throw new Error('仅管理员可配置工作空间');}
    return me;
  }
  function toast(text,error=false){let el=$('#toast');if(!el){el=document.createElement('div');el.id='toast';el.className='toast';el.role='status';document.body.append(el);}el.textContent=text;el.style.background=error?'#872f2c':'';el.hidden=false;clearTimeout(el.timer);el.timer=setTimeout(()=>el.hidden=true,5500);}
  function fail(error){toast(error.message||String(error),true);}
  const statusNames={archived:'旧版存档',queued:'等待执行',running:'执行中',reviewing:'核对证据',completed:'已完成',partial:'部分完成',failed:'执行失败',cancelled:'已取消',interrupted:'已中断',timed_out:'已超时',validation_failed:'回答校验未通过',insufficient_evidence:'证据不足',no_results:'无结果',candidates:'已返回候选',error:'服务错误',INDEXED:'已索引',INDEXING:'索引中',INDEX_FAILED:'索引失败',PREPARING:'准备中',READY:'可用',FAILED:'失败',CONNECTED:'已连接',UNTESTED:'未测试',ERROR:'连接失败'};
  const status=s=>`<span class="tag ${/failed|error|ERROR|FAILED/.test(s)?'error':/partial|insufficient|cancel|interrupt|timed_out/.test(s)?'warn':''}">${esc(statusNames[s]||s)}</span>`;
  const time=value=>value?new Date(value).toLocaleString('zh-CN',{hour12:false}):'—';
  function details(title,value){return `<details><summary>${esc(title)}</summary><pre>${esc(typeof value==='string'?value:JSON.stringify(value,null,2))}</pre></details>`;}
  function dialog(title,html,onSave,confirmLabel='保存') {
    let el=$('#dialog');if(!el){el=document.createElement('dialog');el.id='dialog';document.body.append(el);}
    el.innerHTML=`<div class="dialog-head"><h2>${esc(title)}</h2><button type="button" data-close aria-label="关闭">×</button></div><form id="dialogForm">${html}${onSave?`<div class="dialog-actions"><button type="button" data-close>取消</button><button class="${confirmLabel.includes('删除')?'danger':'primary'}" type="submit">${esc(confirmLabel)}</button></div>`:''}</form>`;
    el.querySelectorAll('[data-close]').forEach(b=>b.onclick=()=>el.close());
    if(onSave)el.querySelector('form').onsubmit=async e=>{e.preventDefault();const button=e.submitter;button.disabled=true;try{await onSave(new FormData(e.target),e.target);el.close();}catch(err){fail(err);}finally{button.disabled=false;}};
    el.showModal();return el;
  }
  async function account(me){dialog('账号',`<p>${esc(me.username)} · ${me.role==='ADMIN'?'管理员':'成员'}</p><label>原密码<input name="currentPassword" type="password" autocomplete="current-password" required></label><label>新密码<input name="newPassword" type="password" minlength="12" maxlength="100" autocomplete="new-password" required></label><p class="sub">至少12位。</p>`,async data=>{await api('/api/auth/password',{method:'POST',body:Object.fromEntries(data)});toast('密码已修改');});}
  return {$,esc,inlineMarkdown,runTimings,api,auth,toast,fail,status,time,details,dialog,account,resetCsrf:()=>csrf=null};
})();
