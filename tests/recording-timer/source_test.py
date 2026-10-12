"""Standalone source regression checks; no Android runtime or device execution.

Run from any directory: python3 tests/recording-timer/source_test.py
"""
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "app/src/main/java/ai/codeandcraft/secondbrain/MainActivity.java"


def method(source, signature):
    start = source.index(signature)
    opening = source.index("{", start)
    depth = 1
    end = opening + 1
    while depth and end < len(source):
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    assert depth == 0, "Unbalanced method"
    return source[opening + 1:end - 1]


def check_timer_contract(source):
    create = method(source, "public void onCreate(Bundle b)")
    save = method(source, "protected void onSaveInstanceState(Bundle b)")
    update = method(source, "private void updateCapture()")
    resolve = method(source, "private void resolveSession(boolean completed)")
    assert 'b.putLong("timerBase",timerBase);' in save, "Save monotonic base"
    assert 'timerBase=b.getLong("timerBase",-1);' in create, "Restore with unavailable default"
    assert ('else if("Record".equals(page)&&capture()&&'
            '(recordingId==null||timerBase<0))resolveSession(false);') in update, "Recover missing ID OR base"
    assert 'recordingId!=null?recordingId.equals(f.getName())' in resolve, "Use known session ID"
    assert ('if(timerBase<0&&result.optLong("startUtc")>0)timerBase='
            'SystemClock.elapsedRealtime()-Math.max(0,System.currentTimeMillis()-'
            'result.optLong("startUtc"));') in resolve, "Existing UTC metadata anchors elapsed estimate"
    assert 'if(!liveCapture&&!completedFinish){if("Record".equals(page))page="Meetings";finishing=false;}' in create and "RecordingStartGate.INSTANCE.canRestore" in create, "Require process-local live capture for saved Record"
    assert 'handleIncomingIntent(getIntent(),b!=null);' in create, "Preserve guarded restored entry"
    assert 'timerBase>=0?duration(Math.max(1,SystemClock.elapsedRealtime()-timerBase)):"Elapsed unavailable"' in update, "Retain unavailable display without a base"


source = SOURCE.read_text()
check_timer_contract(source)
# Ensure each part of the original regression is independently caught.
mutations = [
    ('b.putLong("timerBase",timerBase);', ''),
    ('timerBase=b.getLong("timerBase",-1);', ''),
    ('(recordingId==null||timerBase<0)', 'recordingId==null'),
]
for old, new in mutations:
    assert source.count(old) == 1
    try:
        check_timer_contract(source.replace(old, new))
    except AssertionError:
        pass
    else:
        raise AssertionError("Regression was not detected: " + old)
print("PASS: 8 timer source assertions and 3 regression mutations (source only; no Android runtime)")

# Bind the simulated host contract to actual Activity/service integration.
handler = method(source, "private void handleIncomingIntent(Intent intent,boolean restored)")
start = method(source, "private void startCapture()")
wait = method(source, "private void waitForTranscriptionBeforeCapture()")
create = method(source, "public void onCreate(Bundle b)")
assert 'startPending=b.getBoolean' not in create
assert 'captureRequestOwner=RecordingStartGate.INSTANCE.owner();' in create
assert handler.index('if(restored){render();return;}') < handler.index('if(permitted){record();return;}')
assert 'intent.removeExtra("popPage")' in handler
assert 'RecordingStartGate.INSTANCE.pending()' in method(source, 'private boolean capture()')
assert start.index('if(capture()||finishing)return;') < start.index('RecordingStartGate.INSTANCE.begin(startRequestUtc)')
assert 'RecordingStartGate.INSTANCE.fail(captureRequestOwner)' in start
assert wait.index('AutoTranscription.quiescent()') < wait.index('RecordingStartGate.INSTANCE.claim(captureRequestOwner)') < wait.index('startForegroundService')
assert 'isDestroyed()||!visible' in wait
assert 'RecordingStartGate.INSTANCE.fail(captureRequestOwner)' in wait
assert 'RecordingStartGate.INSTANCE.cancelWaiting(captureRequestOwner)' in method(source, 'private void finishCapture(String stopReason)')
for signature in ('protected void onPause()', 'protected void onDestroy()'):
    body = method(source, signature)
    assert 'releaseCapture' not in body and 'RecordingStartGate' not in body and 'ACTION_STOP' not in body
service = SOURCE.with_name('RecorderService.java').read_text()
assert 'finally { RecordingStartGate.INSTANCE.fail(captureRequestOwner); }' in service
assert 'if (!cleanupRequested && RecordingStartGate.INSTANCE.fail(captureRequestOwner))' in service
assert 'AutoTranscription.releaseCapture();' in method(service, 'public int onStartCommand(')
print('PASS: 16 rotation/start-ownership source integration assertions (source only)')

# Rejected startup/terminal edges: structural checks, not Android execution.
update = method(source, 'private void updateCapture()')
save = method(source, 'protected void onSaveInstanceState(Bundle b)')
assert 'b.putLong("captureRequestOwner",captureRequestOwner)' in save
assert 'captureRequestOwner=b.getLong("captureRequestOwner")' in create
assert 'completedFinish=finishing&&"STOPPED".equals(RecorderService.getStatus())&&!RecorderService.isRecording()&&RecordingStartGate.INSTANCE.canResolveCompleted(captureRequestOwner,startRequestUtc,recordingId)' in create
assert 'finishing&&"STOPPED".equals(s)&&!RecorderService.isRecording()&&RecordingStartGate.INSTANCE.canResolveCompleted(captureRequestOwner,startRequestUtc,recordingId)' in update
assert start.index('page="Record";render();') < start.index('AutoTranscription.requestCapture()')
permission = method(source, 'public void onRequestPermissionsResult(')
assert 'results[0]==PackageManager.PERMISSION_GRANTED)startCapture()' in permission
assert 'if (!recording) return;' not in service
assert 'synchronized (RecorderService.this) {\n                    if (recording)' in service
assert 'synchronized (this)' in method(service, 'private void stopRecording()')
assert service.index('store.complete();') < service.index('RecordingStartGate.INSTANCE.completed(captureRequestOwner, completedId);') < service.index('status = "STOPPED";')
assert 'if (hasAudio) AutoTranscription.enqueueRecorded' in service
assert service.count('AutoTranscription.captureReleased(this, completedId)') == 1
assert 'CAPTURE_COMPLETED' in service
print('PASS: 13 rejected-edge source assertions (permission page, zero-audio completion, terminal ownership; no Android runtime)')
