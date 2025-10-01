package edu.uic.msr.rag

object Ask {
  // Configure these once:
  private val EmbedModel   = "mxbai-embed-large"
  private val AnswerModel  = "llama3.2:1b-instruct-q4_K_M" // small & fast
  private val IndexPath    = "data/chunks.jsonl"           // put your built index here
  private lazy val Index   = SimpleIndex.loadJsonl(IndexPath)

  /** Ask a question against the local corpus. */
  def ask(question: String, k: Int = 4): Either[String, String] = {
    for {
      qEmb <- OllamaClient.embed(EmbedModel, question)
      top  =  SimpleIndex.topK(qEmb, Index, k)
      prompt = PromptBuilder.build(question, top.map(_._1.text))
      ans  <- OllamaClient.generate(AnswerModel, prompt)
    } yield ans
  }

  // quick manual test
  def main(args: Array[String]): Unit = {
    val q = if (args.nonEmpty) args.mkString(" ") else "What does RagMapper do?"
    ask(q) match {
      case Right(text) => println(s"\n=== ANSWER ===\n$text\n")
      case Left(err)   => System.err.println(s"Error: $err")
    }
  }
}
