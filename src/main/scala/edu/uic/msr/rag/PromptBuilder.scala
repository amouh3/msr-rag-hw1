package edu.uic.msr.rag

import org.slf4j.LoggerFactory

/**
 * PromptBuilder:
 *  Creates the final prompt given a user question and a set of context chunks.
 *
 * Contract:
 *  - Does not modify the semantics of the instruction block.
 *  - Numbering of chunks is 1-based and sequential in the same order as `contexts`.
 *  - Returns a single String suitable for direct submission to the generator.
 *
 * Logging:
 *  - DEBUG: number of contexts and aggregate size to aid troubleshooting.
 */
object PromptBuilder {
  private val log = LoggerFactory.getLogger(getClass)

  def build(question: String, contexts: Seq[String]): String = {
    log.debug("PromptBuilder.build: contexts={}, questionChars={}", Int.box(contexts.size), Int.box(question.length))
    val numbered = contexts.zipWithIndex.map { case (c,i) => s"[${i+1}] ${c.trim}" }.mkString("\n---\n")
    s"""SYSTEM:
Answer using ONLY the context chunks below.
Rules:
- If the answer is not in the context, reply exactly: "I don't know based on the MSR corpus."
- Cite ONLY chunk indices from this set: [1..${contexts.size}]. Do NOT invent other numbers.
- Add citations [n] immediately after sentences that use chunk n.
- Do NOT use prior knowledge.

CONTEXT CHUNKS:
$numbered

USER QUESTION:
$question

ASSISTANT:
"""
  }
}
