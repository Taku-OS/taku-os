# Configure runtime permissions, roles and AppOps · Research proposal / not yet tested
# Environment: Root shell on the phone
# Prerequisites: pm grant applies only to valid runtime permissions; notification access, accessibility, roles and AppOps each have their own entry points; undeclared/signature-only permissions cannot be granted this way
# Expected result: Enables notification permission where supported; preserve the original error and cause if refused
# This review did not automatically execute this example; define scenario-specific success criteria first.
pm grant org.agentos.apiboard android.permission.POST_NOTIFICATIONS
appops get org.agentos.apiboard POST_NOTIFICATION
