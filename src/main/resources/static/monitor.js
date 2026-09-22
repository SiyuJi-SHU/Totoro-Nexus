const {esc,api,time,status}=Platform;
const endpoint='/api/platform/admin/monitor';
const colors=['#28765d','#6886c5','#b58335','#9779b4','#539ba4','#bb6f65','#73884f','#77848f'];
const number=value=>value==null?'—':Number(value).toLocaleString('zh-CN');
const short=value=>value>=1e8?`${(value/1e8).toFixed(1)}亿`:value>=1e4?`${(value/1e4).toFixed(1)}万`:number(value);
const sum=(rows,key)=>rows.reduce((n,r)=>n+Number(r[key]||0),0);
const table=(headers,rows)=>`<div class="table-scroll"><table><thead><tr>${headers.map(h=>`<th>${h}</th>`).join('')}</tr></thead><tbody>${rows.join('')}</tbody></table></div>`;
const purposeNames={'task-routing':'意图识别','react-step':'ReAct 推理','answer-synthesis':'答案生成','embedding':'向量化','rerank':'精排'};
const purpose=value=>purposeNames[value]||value;

export async function mount(root,catalog,current){
  if(!document.getElementById('monitor-style')){
    const link=document.createElement('link');link.id='monitor-style';link.rel='stylesheet';link.href='/monitor.css?v=1';document.head.append(link);
  }
  const [usage,runs]=await Promise.all([api(`${endpoint}/usage?days=7`),api(`${endpoint}/runs?page=1&size=10`)]);
  if(!current())return;
  root.innerHTML='<div class="monitor"><div id="monitorUsage"></div><section id="monitorRuns" class="panel monitor-runs" aria-label="运行记录"></section></div>';
  const usageRoot=root.querySelector('#monitorUsage'),runsRoot=root.querySelector('#monitorRuns');
  let usageData=usage,runData=runs,metric='total_tokens',usageRequest=0,runRequest=0;
  const alive=()=>current()&&root.contains(usageRoot);
  const report=(target,error)=>{target.querySelector('.monitor-error')?.remove();const p=document.createElement('p');p.className='error-box monitor-error';p.textContent=error.message||'加载失败，请重试';target.prepend(p);};

  function renderUsage(){
    const groups=usageData.groups,calls=sum(groups,'calls'),unknown=sum(groups,'unknown_usage_calls'),tokens=sum(groups,'total_tokens');
    const models=[...new Set(groups.map(g=>g.model))].sort();
    const tone=model=>colors[models.indexOf(model)%colors.length];
    const byModel=models.map(model=>({model,rows:groups.filter(g=>g.model===model)})).sort((a,b)=>sum(b.rows,metric)-sum(a.rows,metric));
    const max=Math.max(1,...byModel.map(m=>sum(m.rows,metric)));
    const card=(label,value,note)=>`<div class="monitor-stat"><span>${label}</span><strong>${value}</strong><small>${note}</small></div>`;
    usageRoot.innerHTML=`<section class="panel monitor-usage">
      <div class="monitor-heading"><div><span class="monitor-kicker">MODEL ACTIVITY</span><h2>模型调用与用量</h2><p class="sub">${usageData.from} — ${usageData.through} · UTC+8 · 包含聊天、评测、Embedding 与 Rerank</p></div><label class="monitor-select">统计范围<select id="monitorDays" aria-label="统计范围"><option value="7" ${usageData.days===7?'selected':''}>最近 7 天</option><option value="30" ${usageData.days===30?'selected':''}>最近 30 天</option></select></label></div>
      <div class="monitor-stats">${card('模型调用',number(calls),'所选时间范围')}${card('已报告 Token',calls&&unknown===calls?'—':short(tokens),`输入 ${short(sum(groups,'input_tokens'))} / 输出 ${short(sum(groups,'output_tokens'))}`)}${card('调用失败',number(sum(groups,'failed_calls')),'模型请求失败，不等同业务失败')}${card('平均调用耗时',calls?`${(sum(groups,'elapsed_ms')/calls/1000).toFixed(2)} s`:'—',`${number(unknown)} 次调用未报告 Token`)}</div>
      <div class="monitor-heading monitor-chart-heading"><div><h3>${metric==='calls'?'每日模型调用':'每日 Token 用量'}</h3><span class="sub">${metric==='calls'?'按模型堆叠 · 单位：次':'按模型堆叠 · 仅统计已报告 Token'}</span></div><div class="monitor-segments" role="group" aria-label="图表指标"><button data-monitor-metric="total_tokens" aria-pressed="${metric==='total_tokens'}">Token 用量</button><button data-monitor-metric="calls" aria-pressed="${metric==='calls'}">调用次数</button></div></div>
      ${calls?chart(usageData,metric,models,tone):'<div class="empty">所选时间范围内暂无模型调用</div>'}
      <div class="monitor-legend">${models.map(m=>`<span><i style="background:${tone(m)}"></i>${esc(m)}</span>`).join('')}</div>
      <p class="monitor-footnote">Token 来自供应商返回值；未报告的用量不计入合计，此处不估算费用。</p>
    </section>
    <section class="panel monitor-distribution"><div class="monitor-heading"><h3>模型分布</h3><span class="sub">${metric==='calls'?'按调用次数':'按已报告 Token'}排序</span></div>${byModel.length?byModel.map(m=>{const value=sum(m.rows,metric);return `<div class="monitor-model"><span title="${esc(m.model)}">${esc(m.model)}</span><div class="monitor-bar"><i style="width:${value/max*100}%;background:${tone(m.model)}"></i></div><strong>${metric==='total_tokens'&&sum(m.rows,'unknown_usage_calls')===sum(m.rows,'calls')?'未报告':number(value)}</strong><small>${number(sum(m.rows,'calls'))} 次调用</small></div>`;}).join(''):'<p class="sub">暂无数据</p>'}
      <details class="monitor-details"><summary>查看调用明细 · ${groups.length} 组</summary>${table(['任务 / 类型','模型','结果','调用次数','输入 Token','输出 Token','总 Token','平均耗时'],groups.map(g=>`<tr><td>${esc(purpose(g.purpose))}<small class="monitor-block">${esc(g.kind)}</small></td><td>${esc(g.model)}</td><td>${esc(({success:'成功',failed:'失败'})[g.outcome]||g.outcome)}</td><td>${number(g.calls)}</td><td>${number(g.input_tokens)}</td><td>${number(g.output_tokens)}</td><td>${number(g.total_tokens)}${g.unknown_usage_calls?`<small class="monitor-block">${number(g.unknown_usage_calls)} 次未报告</small>`:''}</td><td>${(g.elapsed_ms/g.calls/1000).toFixed(2)} s</td></tr>`))}</details>
    </section>`;
    usageRoot.querySelector('#monitorDays').onchange=async e=>{
      const selected=e.target,days=selected.value,request=++usageRequest;selected.disabled=true;usageRoot.setAttribute('aria-busy','true');
      try{const next=await api(`${endpoint}/usage?days=${days}`);if(!alive()||request!==usageRequest)return;usageData=next;renderUsage();}
      catch(error){if(alive()){selected.value=String(usageData.days);report(usageRoot,error);}}
      finally{if(alive()&&request===usageRequest){selected.disabled=false;usageRoot.setAttribute('aria-busy','false');}}
    };
    usageRoot.querySelectorAll('[data-monitor-metric]').forEach(b=>b.onclick=()=>{metric=b.dataset.monitorMetric;renderUsage();});
    const tooltip=usageRoot.querySelector('.monitor-tooltip');
    usageRoot.querySelectorAll('[data-day-index]').forEach(bar=>{
      const show=()=>{const day=bar.dataset.day,rows=usageData.daily.filter(r=>r.day===day);tooltip.innerHTML=`<b>${day}</b><span>合计：${number(sum(rows,metric))}${metric==='calls'?' 次':' Token'}</span>${rows.map(r=>`<span><i style="background:${tone(r.model)}"></i>${esc(r.model)} <strong>${metric==='total_tokens'&&Number(r.unknown_usage_calls)===Number(r.calls)?'未报告':number(r[metric])}</strong></span>`).join('')}`;tooltip.hidden=false;};
      bar.onmouseenter=bar.onfocus=show;bar.onmouseleave=bar.onblur=()=>{tooltip.hidden=true;};
    });
  }

  function renderRuns(){
    const {items,page,size,total,pages,statuses}=runData;
    const count=s=>Number(statuses.find(r=>r.status===s)?.count||0);
    const start=total?(page-1)*size+1:0,end=Math.min(page*size,total);
    runsRoot.innerHTML=`<div class="monitor-heading"><div><h2>运行记录 <span class="monitor-count">${number(total)}</span></h2><p class="sub">全部历史 · 完成 ${number(count('completed'))} · 证据不足 ${number(count('insufficient_evidence'))} · 失败 ${number(count('failed'))}</p></div><label class="monitor-select">每页<select id="monitorPageSize" aria-label="每页记录数">${[10,20,50].map(n=>`<option value="${n}" ${n===size?'selected':''}>${n} 条</option>`).join('')}</select></label></div>
      ${items.length?table(['时间','智能体 / 版本','来源 / 策略','问题','业务状态','操作'],items.map(r=>`<tr><td class="monitor-time">${time(r.createdAt)}</td><td>${esc(catalog.agents.find(a=>a.id===r.agentId)?.config.name||r.agentId)}<small class="monitor-block">v${r.agentVersion}</small></td><td>${esc(({user:'用户对话',debug:'调试',evaluation:'评测'})[r.origin]||r.origin||'—')}<small class="monitor-block">${esc(({react:'ReAct',workflow:'Workflow',plan_execute_replan:'Plan–Execute–Replan'})[r.strategy]||r.strategy||'—')}</small></td><td class="monitor-question"><span title="${esc(r.question)}">${esc(r.question||'—')}</span></td><td>${status(r.status)}${r.error?`<small class="monitor-block">${esc(r.error)}</small>`:''}</td><td><button data-action="run-details" data-id="${esc(r.id)}">执行详情</button></td></tr>`)):'<div class="empty">暂无运行记录</div>'}
      <nav class="monitor-pagination" aria-label="运行记录分页"><span class="sub">第 ${number(start)}–${number(end)} 条，共 ${number(total)} 条</span><div class="actions"><button data-monitor-page="1" ${page<=1?'disabled':''}>首页</button><button data-monitor-page="${page-1}" ${page<=1?'disabled':''}>上一页</button><span class="monitor-page-number">${page} / ${pages}</span><button data-monitor-page="${page+1}" ${page>=pages?'disabled':''}>下一页</button><button data-monitor-page="${pages}" ${page>=pages?'disabled':''}>末页</button></div></nav>`;
    runsRoot.querySelectorAll('[data-monitor-page]').forEach(b=>b.onclick=()=>loadRuns(Number(b.dataset.monitorPage),size));
    runsRoot.querySelector('#monitorPageSize').onchange=e=>loadRuns(1,Number(e.target.value));
  }
  async function loadRuns(page,size){
    const request=++runRequest;runsRoot.setAttribute('aria-busy','true');runsRoot.querySelectorAll('button,select').forEach(b=>b.disabled=true);
    try{const next=await api(`${endpoint}/runs?page=${page}&size=${size}`);if(!alive()||request!==runRequest)return;runData=next;renderRuns();}
    catch(error){if(alive()&&request===runRequest){renderRuns();report(runsRoot,error);}}
    finally{if(alive()&&request===runRequest)runsRoot.setAttribute('aria-busy','false');}
  }
  renderUsage();renderRuns();
}

function chart(data,metric,models,tone){
  const days=Array.from({length:data.days},(_,i)=>{const d=new Date(`${data.from}T00:00:00Z`);d.setUTCDate(d.getUTCDate()+i);return d.toISOString().slice(0,10);});
  const values=days.map(day=>data.daily.filter(r=>r.day===day));
  const max=Math.max(1,...values.map(rows=>sum(rows,metric))),ceiling=Math.ceil(max/4)||1,top=ceiling*4;
  const width=1000,left=62,right=18,height=230,bottom=262,step=(width-left-right)/days.length;
  const grid=Array.from({length:5},(_,i)=>{const y=height-height*i/4+12;return `<line x1="${left}" y1="${y}" x2="${width-right}" y2="${y}" class="monitor-gridline"/><text x="${left-10}" y="${y+4}" text-anchor="end">${short(top*i/4)}</text>`;}).join('');
  const bars=days.map((day,i)=>{let y=height+12;const rows=values[i],total=sum(rows,metric),x=left+i*step+step*.22,w=step*.56;
    const segments=models.map(model=>{const value=sum(rows.filter(r=>r.model===model),metric),h=value/top*height;y-=h;return h?`<rect x="${x}" y="${y}" width="${w}" height="${h}" fill="${tone(model)}"/>`:'';}).join('');
    return `<g tabindex="0" role="img" aria-label="${day}，${metric==='calls'?'调用':'已报告 Token'} ${total}" data-day-index="${i}" data-day="${day}"><rect class="monitor-day-hit" x="${left+i*step}" y="8" width="${step}" height="${height+8}"/>${segments}<title>${day} · ${number(total)} ${metric==='calls'?'次':'Token'}</title></g>${days.length<=7||i%5===0||i===days.length-1?`<text x="${left+(i+.5)*step}" y="${bottom}" text-anchor="middle">${day.slice(5)}</text>`:''}`;
  }).join('');
  return `<div class="monitor-chart"><div class="monitor-chart-scroll"><svg viewBox="0 0 ${width} 278" aria-label="${metric==='calls'?'每日模型调用次数':'每日已报告 Token 用量'}" role="group">${grid}${bars}</svg></div><div class="monitor-tooltip" hidden></div></div>`;
}
