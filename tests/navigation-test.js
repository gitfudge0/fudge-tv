'use strict';
const assert=require('node:assert/strict');
const nav=require('../app/src/main/assets/ui/navigation.js');
const button={x:100,y:100,width:100,height:40};
assert.equal(nav.nearest(button,[{x:220,y:100,width:100,height:40},{x:240,y:600,width:100,height:40}],'right'),0);
assert.equal(nav.nearest(button,[{x:10,y:100,width:70,height:40}],'right'),-1);
assert.equal(nav.clamp(0,-1,6),0);assert.equal(nav.clamp(5,1,6),5);assert.equal(nav.clamp(2,1,6),3);assert.equal(nav.clamp(0,1,0),-1);
const queue=[{x:1514,y:250,width:266,height:150},{x:1514,y:430,width:266,height:150},{x:1514,y:610,width:266,height:150}];
assert.equal(nav.nearest(queue[0],queue.slice(1),'down'),0);
assert.equal(nav.nearest(queue[1],[button,queue[0]],'up'),1);
console.log('Navigation contracts: 8 passed');
const rows=['nav','gallery','features','queue','apps','provider-a','discovery'],counts={nav:3,gallery:2,features:4,queue:6,apps:7,'provider-a':5,discovery:24};
assert.deepEqual(nav.homeStep('gallery',0,'left',rows,counts),{zone:'gallery',index:0});
assert.deepEqual(nav.homeStep('gallery',0,'right',rows,counts),{zone:'gallery',index:1});
for(const [from,direction,to] of [['gallery','up','nav'],['nav','down','gallery'],['gallery','down','features'],['features','down','queue'],['features','up','gallery'],['queue','down','apps'],['apps','down','provider-a'],['provider-a','down','discovery'],['discovery','up','provider-a'],['provider-a','up','apps'],['apps','up','queue'],['queue','up','features']])assert.equal(nav.homeStep(from,0,direction,rows,counts).zone,to);
assert.equal(nav.homeStep('nav',2,'right',rows,counts).index,2);
assert.equal(nav.homeStep('apps',0,'left',rows,counts).index,0);
assert.equal(nav.homeStep('queue',2,'right',rows,counts).index,3);
const fs=require('node:fs'),path=require('node:path');
const html=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/ui/index.html'),'utf8'),js=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/ui/app.js'),'utf8');
assert.match(html,/<div class="weather"/);assert.doesNotMatch(html,/<button[^>]*class="weather"/);
assert.match(html,/id="heroCopy" role="region"/);assert.doesNotMatch(html,/id="heroCopy" tabindex/);assert.match(js,/renderRail\(\);focus\(heroTarget\(\),false\)/);
assert.ok(html.indexOf('id="homeApps"')<html.indexOf('id="providerRows"')&&html.indexOf('id="providerRows"')<html.indexOf('id="rail"'));
console.log('Home navigation/startup/content contracts passed');
const realProgram={id:'watch:owner:42',title:'Actual shared title',description:'Actual published description',art:'https://appassets.androidplatform.net/art/opaque',owner:'Retained app',resumable:true};
const watchHero=nav.heroForProgram(realProgram);
assert.equal(watchHero.title,realProgram.title);assert.equal(watchHero.description,realProgram.description);assert.equal(watchHero.art,realProgram.art);assert.equal(watchHero.owner,realProgram.owner);assert.equal(watchHero.action,'Resume');
assert.deepEqual(nav.galleryActivation(watchHero),{op:'resume',id:realProgram.id});
assert.equal(nav.heroForProgram({...realProgram,resumable:false}).action,'Open app');
assert.deepEqual(nav.galleryActivation(nav.heroForProgram({...realProgram,resumable:false})),{op:'resume',id:realProgram.id});
assert.equal(nav.heroForProgram({}),null);assert.equal(nav.galleryActivation(null),null);
assert.equal(nav.resetsViewport(nav.homeStep('queue',3,'up',rows,counts).zone),true);
assert.equal(nav.resetsViewport(nav.homeStep('gallery',0,'up',rows,counts).zone),true);
assert.equal(nav.resetsViewport('apps'),false);
console.log('Real-program hero/action and top-navigation reset contracts passed');
assert.equal(nav.maySettleStartup(false,null),true);assert.equal(nav.maySettleStartup(true,null),false);assert.equal(nav.maySettleStartup(false,'settings'),false);assert.equal(nav.maySettleStartup(true,'search'),false);
console.log('Startup focus-settle interaction/modal guards passed');
assert.equal(nav.resetsViewport(nav.homeStep('apps',4,'up',rows,counts).zone),false);
console.log('Apps-to-Continue-Watching preserves the row viewport contract passed');
const refreshedProgram={...realProgram,title:'Updated title',description:'Updated provider description',resumable:false};
assert.equal(nav.reconcileWatchHero(watchHero,[refreshedProgram]).description,refreshedProgram.description);
assert.equal(nav.reconcileWatchHero(watchHero,[refreshedProgram]).action,'Open app');
assert.equal(nav.reconcileWatchHero(watchHero,[]),null);assert.equal(nav.reconcileWatchHero(null,[realProgram]),null);
console.log('Updated/removed Continue Watching hero reconciliation contracts passed');

// Timed gallery does not advance while paused, and resumes from a fresh interval.
assert.deepEqual(nav.carouselStep(7900,100,true),{elapsed:0,advance:true});
assert.deepEqual(nav.carouselStep(7900,100,false),{elapsed:0,advance:false});
assert.deepEqual(nav.carouselStep(0,100,true),{elapsed:100,advance:false});
assert.deepEqual(nav.carouselStep(3000,20,true),{elapsed:3020,advance:false});
// Do not guess at episode titles, remakes or ambiguous movie/TV names.
const exact={title:'Dune: Part Two',key:'movie:tt15239678'};
assert.equal(nav.matchProgram('DUNE — Part Two',[exact]),exact);
assert.equal(nav.matchProgram('Dune S01 E02',[exact]),null);
assert.equal(nav.matchProgram('Dune: Part Two',[exact,{...exact,key:'series:tt0133093'}]),null);
assert.equal(nav.matchProgram('',[{title:''}]),null);
const enriched={...watchHero,description:'Cinemeta synopsis',art:'Cinemeta art'};
assert.deepEqual(nav.galleryActivation(enriched),{op:'resume',id:realProgram.id});
// A held horizontal key crosses consecutive items and clamps only at the end.
let cursor=0;for(let i=0;i<20;i++)cursor=nav.homeStep('queue',cursor,'right',rows,counts).index;
assert.equal(cursor,5);for(let i=0;i<3;i++)cursor=nav.homeStep('queue',cursor,'left',rows,counts).index;assert.equal(cursor,2);
console.log('Gallery timing, conservative metadata matching, resume identity and held queue traversal passed');

const residentTv={title:'Resident Alien',mediaType:'series'},residentMovie={title:'Resident Alien',mediaType:'movie'};
assert.equal(nav.matchProgram('Resident Alien',[residentTv,residentMovie]),null);
assert.equal(nav.matchProgram('Resident Alien',[residentTv,residentMovie],true),residentTv);
assert.equal(nav.matchProgram('Resident Alien',[residentTv,{...residentTv,key:'series:tt15520258'}],true),null);
assert.equal(nav.matchProgram('Resident Alien',[residentMovie],true),null);
console.log('Provider episodic metadata disambiguates TV only; duplicate TV remains unmatched');

// Only valid provider progress may create a remaining-time label or playback bar.
assert.deepEqual(nav.playbackProgress(30000,60000),{percent:50,remaining:1});
assert.deepEqual(nav.playbackProgress(0,60000),{percent:0,remaining:1});
assert.deepEqual(nav.playbackProgress(60000,60000),{percent:100,remaining:0});
for(const [position,duration] of [[undefined,60000],[-1,60000],[70000,60000],[0,0],[NaN,60000],[0,Infinity],['20',60000]])assert.equal(nav.playbackProgress(position,duration),null);
assert.equal(nav.restorationIndex(4,2),1);assert.equal(nav.restorationIndex(4,0),-1);assert.equal(nav.restorationIndex(1,5),1);
console.log('Provider playback validity and row-removal focus clamping passed');

// Hero actions never change the slide; indicators form their own reversible row.
assert.deepEqual(nav.homeStep('gallery',1,'right',rows,counts),{zone:'gallery',index:1});
assert.deepEqual(nav.homeStep('features',0,'left',rows,counts),{zone:'features',index:0});
assert.deepEqual(nav.homeStep('features',1,'right',rows,counts),{zone:'features',index:2});
assert.equal(nav.resetsViewport('features'),true);
assert.equal(nav.homeStep('gallery',0,'down',['nav','gallery','queue','apps'],{gallery:1,queue:1}).zone,'queue');
assert.equal(nav.homeStep('features',1,'down',['nav','gallery','features','apps'],counts).zone,'apps');
console.log('Hero actions and slide indicators traverse separate rows with empty-row fallbacks');
