# Own-track effects / research into system input effects · Research proposal / not yet tested
# Environment: Read-only source-location command in an AOSP checkout; not evidence of implemented functionality
# Prerequisites: First test AEC/NS on an owned AudioRecord session; cross-app/Bluetooth/cellular paths require device integration
# Expected result: Locates effect types/services; system-wide unified effects remain research
# This review did not automatically execute this example; define scenario-specific success criteria first.
rg --threads 2 "EFFECT_TYPE_NS|EFFECT_TYPE_AEC" frameworks/av -g "*.h" -g "*.cpp"
