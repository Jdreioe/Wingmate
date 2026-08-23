# Own communication in one shared session

A shared Kotlin Communication session module owns the active and Held Messages, structured editing rules, persistence, speech coordination, fallback, and clearing. Native clients observe its state, render it, and send communication actions through one interface instead of reproducing session behavior in each client.
