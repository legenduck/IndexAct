package org.indexact.read;

/** Selection within the lexicographically ordered occurrence set of an AROUND anchor. */
public sealed interface OccurrenceSelector permits FirstSelector, NthSelector, AllSelector {}
