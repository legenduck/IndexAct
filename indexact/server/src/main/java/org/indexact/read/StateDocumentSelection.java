package org.indexact.read;

/** A state READ's already-resolved document selection. */
public sealed interface StateDocumentSelection permits AllDocuments, PageSelection {}
