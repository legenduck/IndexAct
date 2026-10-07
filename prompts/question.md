Answer the following question. The answer is contained in the indexed corpus. **Do Not use web search!**

In this interface, search consists of operations that create and manipulate document sets (states). States produced by ranking, filtering, and set operations can be reused in subsequent exploration and reading. Reading provides source text at selected locations within documents in a candidate state or a document already identified.

1. Initially, it may be unclear how the question's clues are expressed in the documents or whether the supporting facts occur in a single document. Ranking is suitable for inspecting candidates while leaving this uncertainty unresolved and finding clues for further exploration in the documents themselves. A few distinctive terms from the question can serve as relevance clues rather than mandatory conditions. RANK and TOPK can be connected through a binding and executed in one index_execute batch.

2. AROUND returns occurrences of an anchor and their surrounding text, making it useful for inspecting portions of several candidates and choosing a document to examine more closely. For example, a RankedRef handle with a page limit of 5 allows a reading request over the first five ranked documents. ANY_OF can combine alternative anchor terms when a clue has several possible expressions. The window and both READ budgets must be specified explicitly. If the first page is insufficient but the ranking remains promising, the same handle and page.next_cursor can provide the next page without a new query. There is no need to exhaust every candidate. AROUND returns anchor occurrences, not a document summary.

3. A short matching passage may not establish whom it concerns, when something happened, or what relationship it describes. Continuous context from a promising document can help establish these relationships. A bounded RANGE from the opening can clarify an unfamiliar document's subject; when a relevant location is known, its surrounding section may be useful. The same document can be read further if the next section contains the needed explanation. DOCUMENT is suitable for inspecting a short document in full. An already observed passage can be cited directly when it provides sufficient evidence.

4. Names, entities, terminology, and unresolved relationships found while reading can become clues for the next search. When another source is needed, CORPUS can be searched again rather than requiring every fact to occur in the current document or candidate set. The same candidates can be explored further while the ranking's hypothesis remains useful, and a new branch can be explored when observed evidence points in another direction.

5. FILTER retains documents that satisfy a condition; INTERSECT and UNION intersect or merge separately created candidate sets. These operations can precisely refine candidates when a required condition is clear. However, a requirement on the answer does not necessarily mean that a particular word must appear in every supporting document. An uncertain clue can instead be used as a relevance signal in RANK, which orders documents without excluding them solely because that word is absent. Useful parent states and alternative branches can be reused later. State-producing operations return cardinality, so there is no need to add COUNT for a state just created in the same batch.

6. If the observed passages sufficiently support the entity and relationship asked about in the question, an answer can be submitted with their evidence IDs without further searching. Repeated searches to reconfirm facts already established are unnecessary.

This guidance describes a general exploration flow, not a sequence that must be followed for every question. A known document or location can be read directly. Only explicit index_read calls return source text; search results do not attach it automatically.

### Tool examples

The following example ranks the corpus and retains a candidate pool in one batch without reading source text. COMBINE combines BM25 term scores as relevance signals; it is not an AND condition requiring every term to occur. A pool of 20 is an example size that permits multiple pages, not a requirement to read all 20 documents.

```json
{
  "operations": [
    {
      "op": "RANK",
      "target": "CORPUS",
      "scoring": {
        "combine": [
          {"term": "mars"},
          {"term": "crater"},
          {"term": "survey"}
        ]
      },
      "bind": "ranked"
    },
    {
      "op": "TOPK",
      "target": {"binding": "ranked"},
      "k": 20,
      "bind": "candidates"
    }
  ]
}
```

The persistent handle returned by TOPK can be used in a subsequent index_read. RANK itself preserves membership, including zero-score documents, and only determines order. Its cardinality is therefore not a count of documents matching the ranking terms. TOPK is the operation that limits the candidate pool to at most 20 documents in this example.

The following example reads a continuous opening range after a document has been selected. The doc_key is illustrative and assumes a document at least 2,000 codepoints long. An actual request uses a received doc_key and valid bounds for that document. Offsets are Unicode codepoints, not line numbers or tokens, and end is exclusive.

```json
{
  "target": {"doc_key": "received-document-key"},
  "documents": null,
  "region": {
    "range": {
      "start": 0,
      "end": 2000
    }
  },
  "budget": {
    "max_output_codepoints": 2000,
    "max_evidence_count": 1
  }
}
```

Reading can continue from the returned end offset with another RANGE within the document's length. A request around a known location can also include its preceding heading or explanation. An isolated keyword passage may omit the affiliation, timing, or relationship needed to interpret it.

QUESTION:
{query}
