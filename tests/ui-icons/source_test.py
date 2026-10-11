"""Focused host accessibility/entry checks; Android layout needs runtime review."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / "app/src/main/java/ai/codeandcraft/secondbrain"
main = (BASE / "MainActivity.java").read_text()
detail = (BASE / "MeetingDetailActivity.java").read_text()
pop = (BASE / "PopUi.java").read_text()
icon = (BASE / "MarginIconButton.java").read_text()

assert '"Record a meeting"' not in main
assert 'if("Record".equals(name))record();else{if(!page.equals(name))PopUi.feedback(v,"tab");page=name;render();}' in main
assert main.index('header.addView(PopUi.sound(this)') < main.index('header.addView(about,infoParams)')
assert 'PopUi.add(content,about' not in main
assert 'Unverified notes' in main
assert 'Counts: validated briefs only' not in main
assert 'if(unavailable>0)PopUi.add(content,PopUi.text(this,unavailable+" meeting briefs unavailable; task counts exclude them.",18,false),12);' in main
# Explicit widths bound the weighted header and long text to their parent.
# These are source contracts, not measured Android touch-target acceptance.
assert 'scroll.addView(content,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT))' in main
assert 'content.addView(header,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT))' in main
assert 'header.addView(brand,new LinearLayout.LayoutParams(0,-2,1))' in main
assert 'header.addView(PopUi.sound(this),new LinearLayout.LayoutParams(PopUi.dp(this,48),PopUi.dp(this,48)))' in main
assert 'infoParams=new LinearLayout.LayoutParams(PopUi.dp(this,48),PopUi.dp(this,48))' in main
assert 'card.addView(done,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT))' in main
# Title, empty state, cards and their text use the existing bounded add helper.
assert 'LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2)' in pop
assert 'frame.addView(text(c,label,38.7f,true),new FrameLayout.LayoutParams(-1,-2))' in pop
assert 'PopUi.add(content,PopUi.homeTitle(this,' in main
# Failed or incomplete indexing must not claim that there are no recordings.
assert 'if(meetings.isEmpty())PopUi.add(content,PopUi.text(this,indexFailures>0||indexIncomplete?"Saved meetings could not be loaded. Try reopening the app.":"No recordings yet.",21.4f,false),20);' in main
assert 'PopUi.add(content,PopUi.text(this,"No recordings yet.",21.4f,false),20)' not in main
assert 'PopUi.add(card,PopUi.text(this,title(m),21.4f,true),0)' in main
for action in ('PLAY,"Play audio",true', 'PAUSE,"Pause audio",false', 'EXPORT,"Export meeting",false'):
    assert 'MarginIconButton.Icon.' + action in detail
assert 'play.setOnClickListener' in detail and 'pause.setOnClickListener' in detail
assert 'exportButton.setOnClickListener(v->showExportSheet())' in detail
assert 'exportMeeting.setOnClickListener(v->{PopUi.feedback(v,"button");showExportSheet();})' in detail
assert 'export.setOnClickListener(v->detail(m,"Summary",true))' in main
assert 'exportButton.setEnabled(false)' in detail and 'exportButton.setEnabled(true)' in detail
assert 'ACTION_SEND' not in icon and 'ACTION_SEND' not in pop
assert 'setContentDescription(label)' in icon and 'setTooltipText(label)' in icon
for dimension in ('MinWidth', 'MinimumWidth', 'MinHeight', 'MinimumHeight'):
    assert 'set' + dimension + '(PopUi.dp(context,48))' in icon
assert 'isEnabled() ? 255 : 96' in icon
assert 'case SOUND_ON:' in icon and 'case SOUND_OFF:' in icon
assert 'setIcon(next?MarginIconButton.Icon.SOUND_ON:MarginIconButton.Icon.SOUND_OFF)' in pop
assert 'b.setContentDescription(label);b.setTooltipText(label);cue(b);' in pop
assert 'putBoolean("sound",next).apply()' in pop
assert 'setSoundEffectsEnabled(false)' in icon
engine=(BASE / "UiSoundEngine.java").read_text()
gate=(BASE / "UiSoundGate.java").read_text()
assert 'RecordingStartGate.INSTANCE.pending(), PopUi.capturePending' in engine
assert 'AutoTranscription.captureRequested(), RecorderService.isRecording(), RecorderService.getStatus()' in engine
assert 'return pending || uiPending || requested || recording' in gate
assert '!("STOPPED".equals(status) || "IDLE".equals(status) || "ERROR".equals(status))' in gate
print('PASS: UI icon labels/tooltips, target sizes, disabled styling, sound state, bottom Record and local export source contracts (host only)')


# Release source contracts only; host checks do not measure phone geometry.
for forbidden in ('keepRootInsideVisibleWindow', 'HorizontalViewportPadding', 'MarginViewport'):
    assert forbidden not in main, 'Unexpected viewport helper or diagnostic tag: ' + forbidden
assert 'technicalhomepageCounts' not in main
assert 'about.setOnClickListener(v->{PopUi.feedback(v,"button");showAbout();})' in main
assert 'PopUi.text(this,"JotterBook",21,true)' in main
assert 'MarginIconButton.Icon.INFO,"About JotterBook",false' in main
assert '.setTitle("JotterBook")' in main
manifest = (ROOT / 'app/src/main/AndroidManifest.xml').read_text()
assert 'android:label="JotterBook"' in manifest
build = (ROOT / 'app/build.gradle.kts').read_text()
assert 'versionCode = 67' in build and 'versionName = "1.0-pilot67"' in build
print('PASS: candidate67 JotterBook identity, truthful empty/unavailable state, About callback and absence of viewport helpers/diagnostic tag (source only; phone geometry unmeasured)')

# Preserve canonical task save target and guard while permitting explicit feedback.
assert 'done.setEnabled(!capture())' in main and 'edit.setEnabled(!capture())' in main
assert 'done.setChecked(item.optBoolean("done"));saveTask(m,index,null,null,null,!item.optBoolean("done"),done);' in main
assert 'save.setOnClickListener(v->saveTask(m,i,text.getText().toString(),owner.getText().toString(),date.getText().toString(),item.optBoolean("done"),save))' in main
save=main.split('private void saveTask(',1)[1].split('private void indexLimitNote',1)[0]
assert 'MeetingBriefStore.append(this,SessionIndexPaths.candidate(' in save
assert save.index('MeetingBriefStore.append(') < save.index('if(visible&&PopUi.soundCurrent(soundGeneration))PopUi.feedback(cue,"save")')
assert 'if(isDestroyed())return;' in save
assert 'getPackageManager().getPackageInfo(getPackageName(),0)' in main
assert 'TextView brand=text("JotterBook",18);' in detail
print('PASS: canonical task save wiring, guarded success feedback and detail JotterBook source identity')

nav=(BASE/'NotebookNav.java').read_text()
assert 'boolean primary="Record".equals(name)' in nav
assert 'PopUi.button(c,label,primary)' in nav
assert 'primary?Color.WHITE:PopUi.INK' in nav
assert 'b.setElevation(primary?PopUi.dp(c,3):0)' in nav
assert 'if(!primary)b.setBackground(new RippleDrawable' in nav
assert 'b.setStateListAnimator(null)' in nav
assert 'b.setSelected(selected)' in nav and 'Paint.UNDERLINE_TEXT_FLAG' in nav and 'Typeface.BOLD' in nav
assert 'b.setContentDescription(b.getText()+(selected?", selected":""))' in nav
assert 'setCompoundDrawables(null,icon,null,null)' in nav
for dim in ['MinWidth','MinimumWidth']:assert 'set'+dim+'(PopUi.dp(c,48))' in nav
for dim in ['MinHeight','MinimumHeight']:assert 'set'+dim+'(PopUi.dp(c,76))' in nav
assert 'setTextSize(16)' in nav and 'setTextScaleX' not in nav
assert '"Meetings".equals(name)' in nav and '"Record".equals(name)' in nav
assert 'c.drawRoundRect' in nav and 'c.drawArc' in nav and 'c.drawLine' in nav
for activity in [main,detail]:
    assert activity.count('new String[]{"Meetings","Record","Tasks"}')==1
    assert 'NotebookNav.button(this,name,name' in activity
    assert '"▤"' not in activity and '"✓"' not in activity
assert 'NotebookNav.select(bottomMeetings,!"Tasks".equals(selectedTab))' in detail
assert 'NotebookNav.select(bottomTasks,"Tasks".equals(selectedTab))' in detail
assert '0xFFCFDCFF' not in main and 'PopUi.CORAL,()->{page="Tasks";render();}' in main
assert 'minSdk = 26' in build
print('PASS: native labelled nav, drawn calendar/mic/check, always-primary raised Record, selected state, 76dp minimum and warm quick card (source only)')
