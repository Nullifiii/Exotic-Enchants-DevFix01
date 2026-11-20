KillstreakEffects 1.0.1 - anti-farm address fix (source of the replaced classes)

Replaced in the jar: com/killstreakeffects/antifarm/AntiFarmManager*.class (+ new AddressRules*.class).
Only the same-IP check changed; the other four checks are byte-for-byte equivalent in behaviour (proved by the
differential test in test-harness/KsPatchTest.java: 6,761 random decisions, 0 mismatches against the original class).
The original Killstreak source was not available, so only these classes were rebuilt and spliced into the jar.
