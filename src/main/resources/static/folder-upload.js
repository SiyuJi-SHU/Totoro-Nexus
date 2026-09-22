(function(root){
  'use strict';
  const MAX_BYTES=5*1024*1024;
  const SUPPORTED_EXTENSIONS=new Set(['md','txt']);

  function classify(files){
    const accepted=[],skipped=[],invalid=[];
    for(const file of Array.from(files||[])){
      const raw=String(file.webkitRelativePath||file.name||'').replace(/\\/g,'/');
      const parts=raw.split('/');
      const name=parts.at(-1)||'';
      const extension=name.includes('.')?name.slice(name.lastIndexOf('.')+1).toLowerCase():'';
      if(!SUPPORTED_EXTENSIONS.has(extension)){
        skipped.push({name:raw||name,reason:'不支持的文件类型'});
        continue;
      }
      const invalidPath=!raw||raw.length>800||parts.length>12||parts.some(part=>!part||part==='.'||part==='..'||part.startsWith('.')||/[\u0000-\u001f:]/.test(part));
      if(invalidPath){invalid.push({name:raw||name,reason:'文件夹层级或路径不符合要求'});continue;}
      if(!Number.isFinite(file.size)||file.size<=0){invalid.push({name:raw,reason:'文件为空'});continue;}
      if(file.size>MAX_BYTES){invalid.push({name:raw,reason:'文件超过 5MB'});continue;}
      accepted.push({file,name,folder:parts.slice(0,-1).join('/'),path:raw});
    }
    accepted.sort((a,b)=>a.path.localeCompare(b.path,'zh-CN'));
    return {accepted,skipped,invalid};
  }

  function createSummary(selection){
    return {total:selection.accepted.length,indexed:0,updated:0,repaired:0,unchanged:0,failed:selection.invalid.length,skipped:selection.skipped.length,failures:[...selection.invalid]};
  }

  function record(summary,item,result,error){
    if(error){summary.failed++;summary.failures.push({name:item.path,reason:error.message||String(error)});return;}
    const action=['indexed','updated','repaired','unchanged'].includes(result?.action)?result.action:'indexed';
    summary[action]++;
  }

  function completed(summary){return summary.indexed+summary.updated+summary.repaired+summary.unchanged;}
  function summaryText(summary){
    const parts=[`完成 ${completed(summary)} 个`];
    if(summary.failed)parts.push(`失败 ${summary.failed} 个`);
    if(summary.skipped)parts.push(`跳过 ${summary.skipped} 个`);
    return parts.join('，');
  }

  const api={MAX_BYTES,classify,createSummary,record,completed,summaryText};
  if(typeof module==='object'&&module.exports)module.exports=api;else root.FolderUpload=api;
})(typeof globalThis!=='undefined'?globalThis:this);
