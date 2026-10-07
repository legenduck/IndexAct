package org.indexact.read;

/** Region requested from a direct document or state target. */
public sealed interface ReadRegion permits DocumentRegion, RangeRegion, AroundRegion {}
