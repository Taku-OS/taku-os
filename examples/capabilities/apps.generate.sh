# Generate, preview and export standalone APKs on the phone · Verified on an emulator (within the cited evidence)
# Environment: Root shell on the phone
# Prerequisites: React, esbuild, aapt, the DEX shell and test-signing tools are present on the phone; App.jsx/api.mjs contain unrestricted application code; the user signs in to the Agent for code generation
# Expected result: The generated app.apk passes signature verification, installs with pm and starts a real Activity; preview does not replace standalone APK acceptance
# This review did not automatically execute this example; define scenario-specific success criteria first.
cd /data/local/agentos/light-app-lab/free-app-kit
# apps/myapp requires main.jsx/ui.jsx and actual App.jsx; APIs additionally require api.mjs
/system/bin/sh build.sh myapp --no-open
/system/bin/sh package-app.sh myapp org.agentos.myapp MyApp
