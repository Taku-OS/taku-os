# Post an app's own system notifications · Verified on an emulator (within the cited evidence)
# Environment: Root shell on the phone
# Prerequisites: The ordinary app has runtime notification consent and its channel enabled; the experimental shell can already call the mini-app service
# Expected result: Returns actual statistics and posts a system notification; verify its content through the notification UI/system record
# This review did not automatically execute this example; define scenario-specific success criteria first.
/data/local/agentos/light-app-lab/free-app-kit/mini-app call board tasks.notify '{}'
