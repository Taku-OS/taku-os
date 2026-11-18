package org.agentos.memory;
/** Only isolates store unit tests from UI lifecycle; production service is tested via real page events. */
final class ObservationService {static ObservationService current;static void policyChanged(){}static void withdraw(String id){}}
