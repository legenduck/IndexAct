package org.indexact.read;

/** A semantic READ result; budget failure is a normal result, not an operation error. */
public sealed interface ReadResult permits ReadSuccess, ReadBudgetExceeded {
    String status();
}
