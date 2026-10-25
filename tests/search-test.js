'use strict';
// Execute the production search and event functions with a deterministic clock
// and a minimal DOM. No network or framework is required.
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const source=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/ui/app.js'),'utf8');
function production(name){const line=source.split('\n').find(line=>line.startsWith(`function ${name}(`));assert.ok(line,`Missing production function ${name}`);return line;}
const input={id:'search',value:''};
const document={activeElement:input};
let buttons=[];
const host={markup:'',contains:node=>buttons.includes(node),set innerHTML(value){this.markup=value;buttons=[...value.matchAll(/<button\b[^>]*data-focus-key="([^"]+)"[^>]*>/g)].map(match=>({dataset:{focusKey:match[1]}}));},get innerHTML(){return this.markup;}};
document.getElementById=id=>id==='search'?input:null;
document.querySelector=selector=>selector==='#search'?input:selector==='#results'?host:buttons.find(button=>selector===`[data-focus-key="${button.dataset.focusKey}"]`)||null;
const calls=[],timers=new Map();let clock=0,timerId=0;
const state={modal:null,page:'search',searchApps:[{installed:true,package:'org.stremio.tv',name:'Stremio',art:'/art/stremio'},{installed:true,package:'org.netflix.tv',name:'Netflix',art:'/art/netflix'},{installed:false,package:'removed',name:'Stremio old'}],searchItems:[]};
const context={state,document,CSS:{escape:value=>value},serial:0,searchTimer:null,initialFocused:false,
 $:selector=>document.querySelector(selector),picture:value=>value?`background-image:url('${value}')`:'',
 focus:element=>{document.activeElement=element;},native:(op,data={})=>calls.push({op,...data}),
 setTimeout:(fn,delay)=>{const id=++timerId;timers.set(id,{fn,time:clock+delay});return id;},clearTimeout:id=>timers.delete(id)};
vm.createContext(context);
vm.runInContext(['esc','focusKey','restoreKey','keepFocus','card','renderSearch','search','nativeEvent'].map(production).join('\n'),context);
async function advance(ms){clock+=ms;for(const [id,timer] of [...timers])if(timer.time<=clock){timers.delete(id);timer.fn();}await Promise.resolve();}
function type(query){input.value=query;context.search();}
function respond(requestId,items,message){context.nativeEvent('search',{requestId,items,message});}
const movie={key:'movie:tt0133093',title:'The Matrix',mediaType:'movie',year:'1999'};
(async()=>{
 type('Strem');
 assert.match(host.innerHTML,/Stremio/);assert.doesNotMatch(host.innerHTML,/Netflix|Stremio old/);
 assert.match(host.innerHTML,/\/art\/stremio/);assert.equal(calls.filter(call=>call.op==='search').length,0);
 assert.equal(calls.at(-1).op,'cancelSearch');
 await advance(319);assert.equal(calls.filter(call=>call.op==='search').length,0);
 const firstRequest=state.searchRequest;
 type('Stremio');await advance(320);
 const latest=calls.filter(call=>call.op==='search');assert.equal(latest.length,1);assert.equal(latest[0].query,'Stremio');
 assert.notEqual(state.searchRequest,firstRequest);
 respond(firstRequest,[movie]);assert.doesNotMatch(host.innerHTML,/The Matrix/);
 document.activeElement=buttons[0];
 state.modal='details';respond(state.searchRequest,[movie,movie]);state.modal=null;
 assert.equal(document.activeElement.dataset.focusKey,'search-app-org.stremio.tv');
 assert.ok(host.contains(document.activeElement));
 assert.equal((host.innerHTML.match(/The Matrix<\/h3>/g)||[]).length,1);
 document.activeElement=buttons.find(button=>button.dataset.focusKey==='search-movie:tt0133093');
 respond(state.searchRequest,[],'Cinemeta unavailable.');
 assert.equal(document.activeElement,input);assert.match(host.innerHTML,/Stremio/);assert.match(host.innerHTML,/Cinemeta unavailable/);
 type('matrix');await advance(320);const inFlight=state.searchRequest;
 type('');respond(inFlight,[movie]);await advance(500);
 assert.doesNotMatch(host.innerHTML,/The Matrix/);assert.match(host.innerHTML,/Enter a name/);
 assert.equal(calls.filter(call=>call.op==='search').length,2);
 assert.equal(calls.filter(call=>call.op==='cancelSearch').length,4);
 type('net');state.page='home';await advance(320);
 assert.equal(calls.filter(call=>call.op==='search').length,2);
 console.log('Unified search: offline apps/icons, debounce, stale rejection, deduplication, focus preservation/fallback, cancellation and inactive-page guard passed');
})().catch(error=>{console.error(error);process.exitCode=1;});
