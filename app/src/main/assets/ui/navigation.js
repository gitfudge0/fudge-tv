/* Pure deterministic directional scoring, shared by the TV UI and local contract checks. */
(function(scope){
  function clamp(index,delta,count){return count>0?Math.max(0,Math.min(count-1,index+delta)):-1;}
  function score(from,to,direction){
    const dx=to.x+to.width/2-from.x-from.width/2,dy=to.y+to.height/2-from.y-from.height/2;
    const horizontal=direction==='left'||direction==='right';
    const primary=horizontal?dx:dy,cross=horizontal?Math.abs(dy):Math.abs(dx);
    if((direction==='left'||direction==='up')?primary>=-1:primary<=1)return Infinity;
    const overlap=horizontal?Math.min(from.y+from.height,to.y+to.height)-Math.max(from.y,to.y):Math.min(from.x+from.width,to.x+to.width)-Math.max(from.x,to.x);
    return Math.abs(primary)+(overlap>0?cross*.2:cross*2.2)+Math.hypot(dx,dy)*.04;
  }
  function nearest(from,candidates,direction){let best=-1,value=Infinity;for(let i=0;i<candidates.length;i++){const next=score(from,candidates[i],direction);if(next<value){best=i;value=next;}}return best;}
  function homeStep(zone,index,direction,rows,counts){
    if(zone==='gallery'&&(direction==='left'||direction==='right'))return {zone,index,feature:direction==='right'?1:-1};
    const row=rows.indexOf(zone);if(row<0)return {zone:'gallery',index:0};
    if(direction==='up'||direction==='down'){const next=clamp(row,direction==='down'?1:-1,rows.length);return {zone:rows[next],index:0};}
    return {zone,index:clamp(index,direction==='right'?1:-1,counts[zone]||1)};
  }
  const api={clamp,score,nearest,homeStep};scope.FudgeNav=api;if(typeof module!=='undefined')module.exports=api;
})(typeof window==='undefined'?globalThis:window);
