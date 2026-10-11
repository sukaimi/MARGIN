"""Host source contracts, not Android policy or audible acceptance."""
from pathlib import Path
import hashlib
root=Path(__file__).resolve().parents[2]
b=root/"app/src/main/java/ai/codeandcraft/secondbrain"
engine=(b/"UiSoundEngine.java").read_text()
pop=(b/"PopUi.java").read_text()
main=(b/"MainActivity.java").read_text()
detail=(b/"MeetingDetailActivity.java").read_text()
for term in ['RecordingStartGate.INSTANCE.pending()', 'PopUi.capturePending', 'AutoTranscription.captureRequested()', 'RecorderService.isRecording()', 'RecorderService.getStatus()', 'getApplicationContext()', 'setAcceptsDelayedFocusGain(false)', 'AUDIOFOCUS_REQUEST_GRANTED', 'INTERRUPTION_FILTER_ALL', 'RINGER_MODE_NORMAL', 'SOUND_EFFECTS_ENABLED', 'getStreamVolume(ATTRIBUTES.getVolumeControlStream())', 'abandonAudioFocusRequest', 'handler.removeCallbacksAndMessages(null)', 'gate.current(token)', 'pool == p', 'pool.play(id,.30f,.30f']:
    assert term in engine,term
load=engine.split('candidate.setOnLoadCompleteListener',1)[1].split('ceramic = candidate.load',1)[0]
assert '.play(' not in load
assert 'AudioTrack' not in pop
assert 'putBoolean("sound",next).apply();if(next)prepareSound(c);else UiSoundEngine.INSTANCE.release();' in pop
for source in [main,detail]:
    assert 'PopUi.resumeSound(this)' in source and 'PopUi.pauseSound()' in source
    destroy=source.split('protected void onDestroy(){',1)[1].split('super.onDestroy()',1)[0]
    assert 'PopUi.pauseSound' not in destroy and 'PopUi.stopSound' not in destroy
    assert 'PopUi.soundCurrent(soundGeneration)' in source
start=main.split('private void startCapture()',1)[1].split('private ',1)[0]
assert 'PopUi.capturePending=true;PopUi.stopSound();' in start and 'PopUi.feedback' not in start
assert 'player=null;}preparedDuration=0;PopUi.playbackClosed(playbackOwner);' in detail
assert 'private final Object playbackOwner = new Object();' in detail
assert 'play.setOnClickListener(v->{if(player!=null&&preparedDuration>0){PopUi.playbackStarted(playbackOwner);' in detail
assert 'v->{PopUi.playbackStarted(playbackOwner);playExcerptSource' not in detail
assert 'v->{PopUi.playbackStarted(playbackOwner);playClip' not in detail
clip=detail.split('void playClip(',1)[1].split('private final Runnable progress',1)[0]
assert clip.index('clip.sha.equals(sha(clip.audio))') < clip.index('PopUi.playbackStarted(playbackOwner)')
assert 'MediaPlayer candidate=new MediaPlayer();player=candidate;PopUi.playbackStarted(playbackOwner);' in clip
assert 'static final MeetingDetailActivity' not in detail
assert 'setTitle("JotterBook")' in detail and 'measureText' in detail

assert 'pause.setOnClickListener(v->{PopUi.stopSound();' in detail
for original,copy in [('01-muted-ceramic-tap.mp3','ui_ceramic.mp3'),('02-paper-rustle.mp3','ui_paper.mp3'),('03-dry-wood-tap.mp3','ui_wood.mp3')]:
    assert hashlib.sha256((root/'approved-ui-assets'/original).read_bytes()).digest()==hashlib.sha256((root/'app/src/main/res/raw'/copy).read_bytes()).digest()
assert (root/'approved-ui-assets/MARGIN-sound-credits.txt').is_file()
assert 'text("JotterBook",18)' in detail
print('PASS: sound UI/source/lifecycle contracts and exact offline asset copies; device acceptance pending')

# Normal expiry/cancellation retain samples; only mute/lifecycle release them.
def body(signature):
    return engine.split(signature,1)[1].split('\n    }',1)[0]
for signature in ('private void endCue() {', 'void stop() {'):
    section=body(signature)
    assert 'loaded.clear()' not in section and '.release()' not in section
assert 'gate.invalidate()' in body('void stop() {')
assert 'endCue();' in body('void stop() {')
assert 'handler.removeCallbacksAndMessages(null)' in body('void stop() {')
assert 'gate.mute(); stop(); releaseResources();' in body('void release() {')
assert 'loaded.clear()' in body('private void releaseResources() {') and 'old.release()' in body('private void releaseResources() {')
assert 'gate.complete(token)' in engine and 'handler.postDelayed(cleanup,650)' in engine
assert 'focus == request[0] && focusToken == focusSequence && gate.current(token)' in engine
assert 'AudioFocusRequest previous = focus; focus = null;' in engine
assert engine.index('endCue();',engine.index('void play(')) < engine.index('request[0] = new AudioFocusRequest.Builder')
assert 'readinessHandler.post' in load and 'gate.current(token)' not in load
assert 'loaded.add(id)' in load and 'status == 0' in load and 'enabled()' in load
assert 'preload()' not in load and 'handler.postDelayed' not in load
assert 'foreground = false; gate.pause(); stop();' in engine
assert 'gate.releasable(token)' in engine and 'resourceHandler.postDelayed(resourceRelease,180)' in engine
resume=body('void resume(Context context) {')
assert 'resourceHandler.removeCallbacks(resourceRelease)' in resume
assert 'gate.resume(); preload();' in resume
pause=body('void pause() {')
assert '.play(' not in pause and 'releaseResources()' in pause
assert 'AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK' in engine
assert 'AUDIOFOCUS_GAIN_TRANSIENT)' not in engine
play=body('void play(Context context,String kind) {')
assert play.index('gate.eligible(') < play.index('endCue();') < play.index('audio.requestAudioFocus(')
assert 'getIntent().removeExtra(MANUAL_FINISH);' in detail
assert 'saved != null, UiSoundEngine.INSTANCE.muteEpoch()' in detail
assert 'finishMarker.consume(UiSoundEngine.INSTANCE.muteEpoch())' in detail
assert detail.count('INSTANCE.play(this,"finish")') == 1
assert 'PopUi.feedback(content,"finish")' not in main
assert 'detail(result,"Transcript",false,manualFinishCue&&manualFinishSoundEpoch==UiSoundEngine.INSTANCE.muteEpoch());manualFinishCue=false;' in main
assert 'putBoolean("manualFinishCue"' not in main
assert 'finishMarker' not in detail.split('onSaveInstanceState',1)[1].split('super.onSaveInstanceState',1)[0]
assert 'putExtra(MeetingDetailActivity.MANUAL_FINISH,manualFinish)' in main

assert 'if (capture() || gate.playback() || !policy()) { stop(); return; }' in engine
print('PASS: retention, cancellation, mute, readiness-only loading and exact focus request contracts')

assert 'manualFinishSoundEpoch=UiSoundEngine.INSTANCE.muteEpoch();startService(' in main
assert 'manualFinishSoundEpoch' not in main.split('onSaveInstanceState',1)[1]
assert 'android:name=".MeetingDetailActivity" android:exported="false"' in (root/'app/src/main/AndroidManifest.xml').read_text()
assert 'versionCode = 67' in (root/'app/build.gradle.kts').read_text()
print('PASS: mute invalidates finish intent before navigation; marker stays internal and transient')
