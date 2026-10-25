'use strict';
// Run the production page/focus functions against deterministic DOM and frame hooks.
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const source=fs.readFileSync(require('node:path').join(__dirname,'../app/src/main/assets/ui/app.js'),'utf8');
function production(name){const line=source.split('\n').find(line=>line.startsWith(`function ${name}(`));assert.ok(line,name);return line;}
const input={id:'search'},gallery={id:'hero-open'},home={hidden:false},searchPage={hidden:true},overlay={hidden:true,innerHTML:''};
const navHome={id:'nav-home'},navSearch={id:'nav-search'};
const close={id:'close',matches:s=>s==='[data-action="close"]'},secondary={id:'secondary',matches:()=>false},primary={id:'primary',matches:s=>s==='button.primary'},disabled={id:'disabled',disabled:true,matches:s=>s==='button.primary'};
let controls=[close,disabled,secondary,primary];
const elements={'#homePage':home,'#searchPage':searchPage,'#search':input,'#overlay':overlay};
const events=[],frames=[],state={page:'home',modal:null};
const context={state,document:{activeElement:navSearch,body:{style:{}}},window:{scrollTo:value=>events.push(['scroll',value])},modalStack:[],focusEpoch:0,modalEpoch:0,
 $:s=>elements[s],$$:s=>s==='#overlay button,#overlay input'?controls:[],visible:e=>!!e&&!e.disabled,
 focus:(e)=>{assert.ok(e);context.document.activeElement=e;context.focusEpoch++;events.push(['focus',e.id]);},focusKey:e=>e?.id,
 native:op=>events.push(['native',op]),navState:name=>events.push(['nav',name]),resetCarousel:()=>{},heroTarget:()=>gallery,
 restoreKey:key=>({'nav-home':navHome,'nav-search':navSearch}[key]),scrollOptions:v=>v,requestAnimationFrame:fn=>frames.push(fn),
 searchTimer:null,clearTimeout:()=>{},userInteracted:false};
vm.createContext(context);
vm.runInContext(['popupTarget','modal','closeModal','showPage','openHome','openSearch','back','nativeEvent'].map(production).join('\n'),context);
context.openSearch();
assert.equal(state.page,'search');assert.equal(home.hidden,true);assert.equal(searchPage.hidden,false);assert.equal(state.modal,null);
assert.equal(context.document.activeElement,input);assert.deepEqual(events.slice(-2),[['focus','search'],['native','imeOpen']]);
context.modal('<details>','details');assert.equal(state.lastFocus,'search');assert.equal(overlay.hidden,false);
frames.shift()();assert.equal(context.document.activeElement,primary);assert.ok(events.some(e=>e[0]==='native'&&e[1]==='imeHide'));
// Asynchronous detail enrichment refreshes content without resetting user focus.
context.document.activeElement=secondary;context.modal('<enriched details>','details');assert.equal(frames.length,0);assert.equal(context.document.activeElement,secondary);
// Closing a popup restores the Search input and leaves its requests running.
assert.equal(context.closeModal(),true);assert.equal(state.page,'search');assert.equal(context.document.activeElement,input);
assert.ok(!events.some(e=>e[0]==='native'&&e[1]==='cancelSearch'));
// Fall back to the first enabled action; Close is only the last resort.
controls=[close,disabled,secondary];assert.equal(context.popupTarget(),secondary);controls=[close,disabled];assert.equal(context.popupTarget(),close);
context.back();assert.equal(context.document.activeElement,navSearch);assert.equal(state.page,'search');
context.openHome();assert.equal(state.page,'home');assert.equal(home.hidden,false);assert.equal(searchPage.hidden,true);assert.equal(context.document.activeElement,navHome);
context.back();assert.equal(context.document.activeElement,navHome);
assert.ok(events.some(e=>e[0]==='native'&&e[1]==='cancelSearch'));
console.log('Page/focus contracts: immediate Search input/IME, primary popup/fallback, detail refresh, request preservation, Home activation and Back to navigation passed');

// Home closes nested settings/sort-style dialogs and returns to the Home navigation.
state.page='search';context.modal('<settings>','settings');context.modal('<sort>','sort');
context.openHome();assert.equal(state.modal,null);assert.equal(context.modalStack.length,0);
assert.equal(state.page,'home');assert.equal(overlay.hidden,true);assert.equal(context.document.activeElement,navHome);
while(frames.length)frames.shift()();assert.equal(context.document.activeElement,navHome);

context.modal('<settings>','settings');context.nativeEvent('home',{});
assert.equal(state.modal,null);assert.equal(context.document.activeElement,navHome);assert.equal(context.userInteracted,true);
