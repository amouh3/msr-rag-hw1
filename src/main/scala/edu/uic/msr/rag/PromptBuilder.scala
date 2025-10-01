package edu.uic.msr.rag

object PromptBuilder {
  def build(question: String, contexts: Seq[String]): String = {
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
