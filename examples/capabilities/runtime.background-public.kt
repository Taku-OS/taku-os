// Ordinary background tasks, boot notifications and recovery · Research proposal / not yet tested
// Environment: Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI
// Prerequisites: Implement JobService/BootReceiver; persistent tasks remain subject to Doze/quotas/OEM limits; design Direct Boot data separately
// Expected result: RESULT_SUCCESS means the system accepted the job, not that it runs immediately
// This review did not automatically execute this example; define scenario-specific success criteria first.
val info = JobInfo.Builder(42, ComponentName(this, ProbeJobService::class.java)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build()
val result = getSystemService(JobScheduler::class.java).schedule(info)
