package skeptik.proof

import skeptik.judgment.Judgment
import skeptik.util.prettyPrinting._

abstract class Proof[+J <: Judgment, +P <: Proof[J,P] : ClassManifest] (val name: String, val premises: List[P])
{
  private val self = asInstanceOf[P]
  def conclusion : J
  def parameters: List[Any] = Nil
  override def toString = {
    var counter = 0; var result = "";
    def visitNode(n:P, r:List[Int]): Int = {
      counter += 1
      result += counter.toString + ": {" + n.conclusion + "} \t:" +
                n.name + "(" + csv(r) + ")[" + csv(parameters) + "]\n"
      counter
    }
    ProofNodeCollection(self).foldDown(visitNode)
    result
  }
}