'use strict';
// Exercise the production row animation with a deterministic frame clock.
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const source=fs.readFileSync(require('node:path').join(__dirname,'../app/src/main/assets/ui/app.js'),'utf8');
const production=name=>{const line=source.split('\n').find(line=>line.startsWith(`function ${name}(`));assert.ok(line,name);return line;};
let now=0,reduced=false,nextId=0;const frames=new Map();
const cards=[0,1,2,3].map(i=>{const style={},art={style:{},getBoundingClientRect:()=>({height:parseFloat(art.style.height)|| (i===2?195:150)})};return {style,querySelector:()=>art,getBoundingClientRect:()=>({left:i*110,right:i*110+(parseFloat(style.width)||(i===2?130:100)),width:parseFloat(style.width)||(i===2?130:100)})};});
const row={scrollLeft:0,scrollWidth:460,clientWidth:250,querySelectorAll:()=>cards,getBoundingClientRect:()=>({left:0,right:250})};
const context={document:{activeElement:cards[2]},rowAnimations:new WeakMap(),reduced:()=>reduced,performance:{now:()=>now},requestAnimationFrame:fn=>{const id=++nextId;frames.set(id,fn);return id;},cancelAnimationFrame:id=>frames.delete(id)};
vm.createContext(context);vm.runInContext(production('animateRow'),context);
function frame(time){now=time;const callbacks=[...frames.values()];frames.clear();callbacks.forEach(fn=>fn(time));}
const starts=cards.map(()=>({width:100,height:150}));
context.animateRow(row,starts);assert.equal(row.scrollLeft,0);assert.equal(parseFloat(cards[2].style.width),100);
frame(70);
assert.ok(row.scrollLeft>0&&row.scrollLeft<105,'offscreen item moves into view during expansion');
assert.ok(parseFloat(cards[2].style.width)>100&&parseFloat(cards[2].style.width)<130,'focus expansion is still running');
assert.equal(row.scrollLeft/105,(parseFloat(cards[2].style.width)-100)/30,'scroll and focus share the same progress');
frame(140);assert.equal(row.scrollLeft,105);assert.equal(cards[2].style.width,'');assert.equal(frames.size,0);
// A new focus cancels the preceding frame loop; held keys cannot leave a stale scroll animation.
row.scrollLeft=0;context.animateRow(row,starts);context.document.activeElement=cards[3];context.animateRow(row,starts);assert.equal(frames.size,1);
frame(280);assert.equal(row.scrollLeft,185);assert.equal(frames.size,0);
reduced=true;row.scrollLeft=0;context.document.activeElement=cards[2];context.animateRow(row,starts);assert.equal(row.scrollLeft,105);assert.equal(frames.size,0);
// The Home page no longer renders a queue, and media cards use the poster URL.
const html=fs.readFileSync(require('node:path').join(__dirname,'../app/src/main/assets/ui/index.html'),'utf8');
assert.doesNotMatch(html,/class="queue"/);assert.ok(html.indexOf('id="queue"')<html.indexOf('id="homeApps"'));assert.equal((html.match(/id="homeApps"/g)||[]).length,1);
const markupContext={esc:v=>v,picture:v=>v};vm.createContext(markupContext);vm.runInContext(production('card'),markupContext);
const markup=markupContext.card({key:'movie:1',title:'Movie',poster:'portrait.jpg',art:'landscape.jpg'});
assert.match(markup,/card poster-card/);assert.match(markup,/portrait.jpg/);assert.doesNotMatch(markup,/landscape.jpg/);
console.log('Row motion: simultaneous focus/scroll, interrupted movement, reduced motion; poster selection and single apps row passed');
