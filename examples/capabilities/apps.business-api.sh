# Expose generated-app business functions / external calls · Verified on an emulator (within the cited evidence)
# Environment: Root shell on the phone
# Prerequisites: api.mjs implements arbitrary methods and parameter/result schemas; service at 127.0.0.1:8765; UI/CLI share state; start the service first
# Expected result: ok=true and value=1 m; invalid params/methods return structured errors; HTTP200 alone is not success
# This review did not automatically execute this example; define scenario-specific success criteria first.
/data/local/agentos/light-app-lab/free-app-kit/mini-app describe converter
/data/local/agentos/light-app-lab/free-app-kit/mini-app call converter length.convert '{"value":100,"from":"cm","to":"m"}'
