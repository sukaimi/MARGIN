from pathlib import Path
root=Path(__file__).resolve().parents[2]/'app/src/main/java/ai/codeandcraft/secondbrain'
main=(root/'MainActivity.java').read_text();auto=(root/'AutoTranscription.java').read_text();detail=(root/'MeetingDetailActivity.java').read_text();rec=(root/'RecorderService.java').read_text()
assert '.setNegativeButton("Delete all meetings",(d,w)->confirmCleanSlate())' in main
assert '.setNegativeButton("Cancel",null).setPositiveButton("Delete permanently",(d,w)->deleteAllMeetings())' in main
assert 'Copies already saved outside the app are not deleted.' in main
assert main.count('CleanSlate.deleteMeetingData(')==1
assert 'MeetingDetailActivity.hasOpenWork()||!AutoTranscription.beginCleanSlate()' in main
assert 'finally{AutoTranscription.endCleanSlate();}' in main
assert 'private static final ExecutorService loader=Executors.newSingleThreadExecutor();' in main
assert 'loader.shutdown' not in main
assert 'static synchronized boolean beginCleanSlate()' in auto
assert 'if(cleanSlate||working||blocked()||RecordingStartGate.INSTANCE.pending()' in auto
assert 'static synchronized boolean blocked(){return cleanSlate||' in auto
assert 'if(cleanSlate)return false;JSONArray' in auto
assert 'detailWorkers.removeIf(ExecutorService::isTerminated)' in detail
assert 'private final ExecutorService loader = newDetailWorker();' in detail
assert 'if (AutoTranscription.cleanSlateRequested() || recording' in rec
assert 'cleanSlateRevision!=AutoTranscription.cleanSlateRevision()' in main
assert 'requestedRevision!=AutoTranscription.cleanSlateRevision()' in main
assert 'if(AutoTranscription.cleanSlateRequested())render();else loadIndex();' in main


assert "if(AutoTranscription.cleanSlateRequested()){finish();return;}" in detail
assert "if(isFinishing()||AutoTranscription.cleanSlateRequested()){finish();return;}" in detail
print('PASS: 20 clean-slate UI, lifecycle and worker exclusion source contracts')
