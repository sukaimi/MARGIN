"""Focused index wiring contracts; host checks do not establish device behavior."""
from pathlib import Path
root = Path(__file__).resolve().parents[2]
base = root / "app/src/main/java/ai/codeandcraft/secondbrain"
main = (base / "MainActivity.java").read_text()
paths = (base / "SessionIndexPaths.java").read_text()
load = main.split("    private void loadIndex(){", 1)[1].split("    private static void indexFailure", 1)[0]
assert "SessionIndexPaths.newestFirst(files)" in load
assert "lastModified()" not in load
assert "SessionIndexPaths.candidate(root,f)" in load
assert "SessionIndexPaths.metadata(candidate)" in load
assert "AtomicFile" not in load
assert "FileInputStream(meta)" in paths
assert "catch(Throwable" not in load and "catch(Error" not in load
for stage in ("ROOT_LIST", "ENTRY_SORT", "PATH", "METADATA_READ", "METADATA_PARSE", "ROW_SORT", "BRIEF"):
    assert f'stage="{stage}"' in load
assert load.index('stage="ROW_SORT"') < load.index('rows.sort(')
assert load.index('stage="BRIEF"') < load.index('MeetingBriefStore.load(')
assert load.index('stage="ENTRY_SORT"') < load.index('SessionIndexPaths.newestFirst(')
assert load.index('stage="PATH"') < load.index('if(!f.isDirectory())')
assert load.index('if(!f.isDirectory())') < load.index('directories++') < load.index('SessionIndexPaths.uuidLooking(')
assert 'catch(Exception e){incomplete=true;entryFailed++;' in load
assert 'completed=true;' in load
assert 'finally{\n                // Errors propagate' in load
assert 'if(!completed){incomplete=true;' in load
assert 'finally{\n                    handler.post(' in load
assert 'handler.post(()->{indexBusy=false;if(isDestroyed())return;' in load
assert 'requestedRevision!=AutoTranscription.cleanSlateRevision()){loadIndex();return;}indexReady=true;' in load
assert load.count('handler.post(') == 1
assert load.count('loader.execute(') == 1 and load.count('loadIndex();') == 1
assert 'if(requestedRevision!=AutoTranscription.cleanSlateRevision()){loadIndex();return;}' in load
assert 'indexDiagnostics++<3' in load
assert '.getMessage(' not in load and '.toString(' not in load
assert 'new android.util.AtomicFile(meta).readFully()' in main.split('private static JSONObject read(',1)[1].split('private void detail',1)[0]
assert 'indexFailures>0||indexIncomplete?"Saved meetings could not be loaded.' in main
print('PASS: index helper wiring, readonly metadata, per-stage diagnostics, incomplete state, finally completion and generation-only refresh, no failure retry loop (host source checks)')

save = main.split("    private void saveTask(",1)[1].split("    private void indexLimitNote",1)[0]
assert 'MeetingBriefStore.append(this,SessionIndexPaths.candidate(new File(getFilesDir(),"sessions"),new File(new File(getFilesDir(),"sessions"),m.getString("_id"))),draft,' in save
