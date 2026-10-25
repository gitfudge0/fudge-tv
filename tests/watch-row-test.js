'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const source=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/ui/app.js'),'utf8');
const production=name=>{const line=source.split('\n').find(line=>line.startsWith(`function ${name}(`));assert.ok(line,name);return line;};
const queue={innerHTML:''},calls=[],state={queue:[{id:'real-owner:42',title:'Shared series',owner:'Streaming app',season:2,episode:3,resumable:true,position:30000,duration:60000,art:'/art/real'},{id:'fallback',title:'Shared film',owner:'Other app',resumable:false,position:-1,duration:60000}],watchHero:null};
const context={state,FudgeNav:require('../app/src/main/assets/ui/navigation.js'),$:s=>s==='#queue'?queue:null,setMarkup:(host,html)=>host.innerHTML=html,sharedArt:item=>`<img src="${item.art||''}" alt="">`,native:(op,data)=>calls.push({op,...data})};
vm.createContext(context);vm.runInContext(['esc','renderQueue','action'].map(production).join('\n'),context);
context.renderQueue();assert.match(queue.innerHTML,/data-id="real-owner:42"/);assert.match(queue.innerHTML,/Season 2 · Episode 3/);assert.match(queue.innerHTML,/Streaming app · 1 min left/);assert.match(queue.innerHTML,/width:50%/);assert.equal((queue.innerHTML.match(/watch-progress/g)||[]).length,1);assert.match(queue.innerHTML,/Other app · Open app/);assert.doesNotMatch(queue.innerHTML,/queue-track|is-selected/);
context.action({dataset:{action:'resume',id:'real-owner:42'}});assert.deepEqual(calls,[{op:'resume',id:'real-owner:42'}]);
// Focus scrolls the regular row into view without replacing the featured carousel.
const row={getBoundingClientRect:()=>({top:1200,bottom:1550,height:350})},element={dataset:{id:'real-owner:42'},classList:{add:()=>{}},focus:()=>context.document.activeElement=element,closest:s=>s==='.home-row'||s==='#queue'?row:null};
Object.assign(context,{document:{activeElement:null},visible:()=>true,focusEpoch:0,initialFocused:false,metricTimer:null,setTimeout:()=>1,clearTimeout:()=>{},metrics:()=>{},innerHeight:1080,window:{scrollY:0,scrollTo:value=>calls.push(value)},scrollOptions:v=>v});
vm.runInContext(production('focus'),context);context.focus(element);assert.equal(state.watchHero,null);assert.equal(context.document.activeElement,element);assert.equal(calls.at(-1).top,835);
// Empty shared history stays honest and exposes access settings.
state.queue=[];state.queueStatus='No history shared';context.renderQueue();assert.match(queue.innerHTML,/No history shared/);assert.match(queue.innerHTML,/data-action="settings"/);assert.doesNotMatch(queue.innerHTML,/data-action="resume"/);
const html=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/ui/index.html'),'utf8');assert.ok(html.indexOf('id="queue"')<html.indexOf('id="homeApps"'));assert.match(html,/id="heroCopy"[^>]*><div id="heroInfo"><\/div><div class="hero-bottom">/);
console.log('Shared watch row: actual identity/resume, episode metadata, valid progress only, empty state, independent hero and normal row reveal passed');
