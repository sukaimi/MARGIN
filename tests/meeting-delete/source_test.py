from pathlib import Path
root=Path(__file__).resolve().parents[2]/'app/src/main/java/ai/codeandcraft/secondbrain'
def read(name):return (root/(name+'.java')).read_text()
detail,main,auto,clean,export,bundle,smol=map(read,['MeetingDetailActivity','MainActivity','AutoTranscription','CleanSlate','MeetingExport','SourceBundle','SmolBriefEngine'])
checks=[
 '.setNeutralButton("Delete meeting",(d,w)->confirmDeleteMeeting())' in detail,
 '.setNegativeButton("Cancel",null).setPositiveButton("Delete permanently",(d,w)->deleteMeeting())' in detail,
 'Copies already saved outside the app stay.' in detail,
 'otherMeetingWork()||!AutoTranscription.beginCleanSlate()' in detail,
 'if(!deleting&&!AutoTranscription.cleanSlateRequested()&&!loader.isShutdown())loader.execute(task);' in detail,
 'loader.execute(()->MainActivity.runStorageTask(()->{' in detail,
 'if(deleting)loader.shutdown();else loader.shutdownNow();' in detail,
 'finally{AutoTranscription.endCleanSlate();}' in detail,
 'closePlayer();handler.removeCallbacksAndMessages(null);' in detail,
 'if(isDestroyed()||deleting)return;' in detail,
 'static void runStorageTask(Runnable task){loader.execute(task);}' in main,
 'requestedRevision!=AutoTranscription.cleanSlateRevision()' in main,
 'if(!cleanSlate||working)throw new IOException' in auto,
 'if(!id.equals(candidate))next.put(candidate);' in auto,
 'write(f,record.put("ids",next));' in auto,
 'Files.isSymbolicLink(candidate.toPath())' in auto,
 'List<File> plan = meetingPlan(files, cache, id);' in clean,
 clean.index('List<File> plan = meetingPlan(files, cache, id);') < clean.index('queue.remove();'),
 'throw new LegacyFilesPresent()' in clean,
 'name.startsWith(id + "--")' in clean,
 'root.getName()' in bundle and 'meetingId(' in bundle,
 'meetingId' in export and '"--"' in export,
 'SourceBundle.meetingId(meeting)' in smol and '"--"' in smol,
 'name.startsWith(owner+"--")' in detail and 'name.substring(38,74)' in detail,
 'new FileInputStream(backup.isFile()?backup:f)' in auto,
 'if(deleting||!loaded||meetingDirectory==null)' not in detail,
 'if (plan.remove(metadata)) plan.add(metadata);' in clean and 'if (plan.remove(session)) plan.add(session);' in clean,
]
for i,check in enumerate(checks,1):assert check,i
print(f'PASS: {len(checks)} per-meeting confirmation, lifecycle, queue and ownership source checks')
