package at.logic.skeptik.parser


import collection.mutable.{HashMap => MMap}
import at.logic.skeptik.proof.Proof
import at.logic.skeptik.proof.sequent.{SequentProofNode => Node}
import at.logic.skeptik.proof.sequent.lk.{Axiom, R, UncheckedInference}
import at.logic.skeptik.expression._
import at.logic.skeptik.expression.formula.{All, And, Equivalence, Ex, FormulaEquality, Imp, Neg, Or}
import at.logic.skeptik.expression.term._
import at.logic.skeptik.judgment.immutable.{SeqSequent => Sequent}
import at.logic.skeptik.parser.TPTPParsers.TPTPAST._
import at.logic.skeptik.parser.TPTPParsers.{TPTPLexical, TPTPTokens}

import scala.util.parsing.combinator.syntactical.TokenParsers
import scala.util.parsing.combinator.PackratParsers
import scala.util.parsing.input.Reader


/**
  * This modules describe the TPTP syntax as descrobed in
  * www.cs.miami.edu/~tptp/TPTP/SyntaxBNF.html
  *
  * The non terminals don't follow camelcase convention to
  * reflect the grammar in a more natural way.
  *
  * @author Ezequiel Postan
  * @since 23.05.2016
  * @version 1.0
  * @note This version does not support let expressions
  */

class UnexpectedEmptyTPTPFileException extends Exception("Unexpected Empty File")
class TPTPExtractException extends Exception("Unexpected Extract Exception")


object ProofParserTPTP   extends ProofParser[Node] with ProofParserTPTP
object ProblemParserTPTP extends ProblemParserTPTP

/**
  * The BaseParserTPTP trait implements the common parsers shared
  * both by problems and proof objects described by the TPTP syntax.
  * They return an AST representation of the syntax, logic formulas
  * are translated to their Skeptik internal representation.
  */
trait BaseParserTPTP
extends TokenParsers with PackratParsers {

  val  lexical = new TPTPLexical
  type Tokens  = TPTPTokens

  // Parsing methods
  def parse[Target](input: String, parser: Parser[Target]) = {
    val tokens = new lexical.Scanner(input)
    phrase(parser)(tokens)
  }

  def parse[Target](input: Reader[Char], parser: Parser[Target]) = {
    val tokens = new lexical.Scanner(input)
    phrase(parser)(tokens)
  }

  def tokens(input: String) = {
    new lexical.Scanner(input)
  }

  def tokens(input: Reader[Char]) = {
    new lexical.Scanner(input)
  }


  // Actual Parsers
  import lexical._

  def include: Parser[(String,List[String])] = (
    (elem(Include) ~ elem(LeftParenthesis)) ~> elem("Single quoted", _.isInstanceOf[SingleQuoted])
      ~ opt((elem(Comma) ~ elem(LeftBracket)) ~> repsep(name,elem(Comma)) <~ elem(RightBracket))
      <~ (elem(RightParenthesis) ~ elem(Dot)) ^^ {
      case SingleQuoted(data) ~ Some(names) => (data, names)
      case SingleQuoted(data) ~     _       => (data, List.empty)
    }
  )

  def annotatedPattern(languageToken : Token, expectedFormula : Parser[RepresentedFormula]) =
    (elem(languageToken) ~ elem(LeftParenthesis)) ~> name ~ (elem(Comma) ~> formula_role <~ elem(Comma)) ~ expectedFormula ~ annotations <~ elem(RightParenthesis) ~ elem(Dot)

  private def toAnnotatedFormula(language: Language, name: Name,
                                 role: FormulaRole, formula: RepresentedFormula,
                                 annotations: Annotations) : AnnotatedFormula =
    new AnnotatedFormula(language,name,role,formula,annotations)

  def fof_annotated : Parser[AnnotatedFormula] = annotatedPattern(FOF,fof_formula) ^^
    { case name ~ role ~ formula ~ annotations => toAnnotatedFormula("fof",name,role,formula,annotations) }
  def cnf_annotated : Parser[AnnotatedFormula] = annotatedPattern(CNF,cnf_formula) ^^
    { case name ~ role ~ formula ~ annotations => toAnnotatedFormula("cnf",name,role,formula,annotations) }
  def tff_annotated : Parser[AnnotatedFormula] = annotatedPattern(TFF,tff_formula) ^^
    { case name ~ role ~ formula ~ annotations => toAnnotatedFormula("tff",name,role,formula,annotations) }
  def thf_annotated : Parser[AnnotatedFormula] = annotatedPattern(THF,thf_formula) ^^
    { case name ~ role ~ formula ~ annotations => toAnnotatedFormula("thf",name,role,formula,annotations) }
  def tpi_annotated : Parser[AnnotatedFormula] = annotatedPattern(TPI,tpi_formula) ^^
    { case name ~ role ~ formula ~ annotations => toAnnotatedFormula("tpi",name,role,formula,annotations) }


  def name : Parser[String] = (
    atomic_word
      | elem("integer", _.isInstanceOf[Integer]) ^^ {_.chars}
    )
  def atomic_word: Parser[String] = (
    elem("lower word", _.isInstanceOf[LowerWord])           ^^ {_.chars}
      | elem("single quoted", _.isInstanceOf[SingleQuoted]) ^^ {_.chars}
    )

  def formula_role : PackratParser[String] = {
    def isRecognizedAsFormulaRole(token : Token): Boolean = {
      val acceptedRoles = List("axiom" , "hypothesis" , "definition" , "assumption" ,
                               "lemma" , "theorem" , "corollary" , "conjecture",
                               "negated_conjecture" , "plain" , "type" , "fi_domain" ,
                               "fi_functors" , "fi_predicates" , "unknown")
      token.isInstanceOf[LowerWord] &&  acceptedRoles.contains(token.chars)
    }
    val expectedWord = "axiom, hypothesis, definition, assumption, lemma, theorem, corollary, conjecture, " +
                       "negated_conjecture, plain, type, fi_domain, fi_functors, fi_predicates or unknown"
    elem(expectedWord, isRecognizedAsFormulaRole) ^^ {_.chars}
  }

  def annotations : Parser[Annotations] =
    opt(elem(Comma) ~> source ~ optional_info) ^^ {
      case None => None
      case Some(src ~ info) => Some((src,info))
    }

  def source : PackratParser[Source] = general_term
  def optional_info : Parser[List[GeneralTerm]] =  opt(elem(Comma) ~> useful_info) ^^ {
    case None => List.empty
    case Some(x) => x
  }

  def useful_info: Parser[List[GeneralTerm]] = general_list

  // Non-logical data (GeneralTerm, General data)
  def general_term: Parser[GeneralTerm] = (
    general_list                              ^^ {x => GeneralTerm(List(Right(x)))}
      ||| general_data                              ^^ {x => GeneralTerm(List(Left(x)))}
      ||| general_data ~ elem(Colon) ~ general_term ^^ {case data ~ _ ~ gterm => GeneralTerm(Left(data) :: gterm.term)}
    )

  def general_list: Parser[List[GeneralTerm]] =
    elem(LeftBracket) ~> opt(general_terms) <~ elem(RightBracket) ^^ {
      case Some(gt)   => gt
      case _       => List.empty
    }
  def general_terms: Parser[List[GeneralTerm]] = rep1sep(general_term, elem(Comma))

  def general_data: Parser[GeneralData] = (
    atomic_word                                             ^^ {GWord(_)}
      ||| general_function
      ||| variable                                                ^^ {GVar(_)}
      ||| number                                                  ^^ {GNumber(_)}
      ||| elem("Distinct object", _.isInstanceOf[DistinctObject]) ^^ {x => GDistinct(x.chars)}
      ||| formula_data                                            ^^ {GFormulaData(_)}
    )

  def variable: Parser[String] = elem("Upper word", _.isInstanceOf[UpperWord]) ^^ {_.chars}

  def number: Parser[String] = (
        elem("Integer" , _.isInstanceOf[Integer] ) ^^ {_.chars}
      | elem("Real"    , _.isInstanceOf[Real]    ) ^^ {_.chars}
      | elem("Rational", _.isInstanceOf[Rational]) ^^ {_.chars}
    )

  def general_function: Parser[GFunc] =
    atomic_word ~ elem(LeftParenthesis) ~ general_terms ~ elem(RightParenthesis) ^^ {
      case name ~ _ ~ args ~ _  => GFunc(name,args)
    }

  def formula_data : Parser[FormulaData] = (
    (acceptIf(x => x.isInstanceOf[DollarWord] && x.chars.equals("$thf"))(_ => "Parse error in formulaData") ~ elem(LeftParenthesis)) ~>
      thf_formula <~ elem(RightParenthesis) ^^ {GFormulaDataFormula("$thf",_)}
      | (acceptIf(x => x.isInstanceOf[DollarWord] && x.chars.equals("$tff"))(_ => "Parse error in formulaData") ~ elem(LeftParenthesis)) ~>
      tff_formula <~ elem(RightParenthesis) ^^ {GFormulaDataFormula("$tff",_)}
      | (acceptIf(x => x.isInstanceOf[DollarWord] && x.chars.equals("$fof"))(_ => "Parse error in formulaData") ~ elem(LeftParenthesis)) ~>
      fof_formula <~ elem(RightParenthesis) ^^ {GFormulaDataFormula("$fof",_)}
      | (acceptIf(x => x.isInstanceOf[DollarWord] && x.chars.equals("$cnf"))(_ => "Parse error in formulaData") ~ elem(LeftParenthesis)) ~>
      cnf_formula <~ elem(RightParenthesis) ^^ {GFormulaDataFormula("$cnf",_)}
      | (acceptIf(x => x.isInstanceOf[DollarWord] && x.chars.equals("$fot"))(_ => "Parse error in formulaData") ~ elem(LeftParenthesis)) ~>
      term <~ elem(RightParenthesis) ^^ {GFormulaDataTerm("$fot",_)}
    )

  def term: Parser[E] = (
    function_term
      ||| variable         ^^ {Variable(_)}
      ||| conditional_term
      ||| let_term
    )

  def function_term: Parser[E] = (
    plain_term
      | defined_plain_term
      | system_term
      | number                                                  ^^ {NumberTerm(_)}
      | elem("Distinct object", _.isInstanceOf[DistinctObject]) ^^ {x => DistinctObjectTerm(x.chars)} // TODO: How to encode this...
    )



  def plain_term: Parser[E] =
    constant ~ opt(elem(LeftParenthesis) ~> arguments <~ elem(RightParenthesis)) ^^ {
      case c ~ Some(x) => FunctionTerm(c,x)
      case c ~ _       => Constant(c)
    }

  def constant: Parser[String] = atomic_word

  def defined_plain_term: Parser[E] =
    atomic_defined_word ~ opt(elem(LeftParenthesis) ~> arguments <~ elem(RightParenthesis)) ^^ {
      case c ~ Some(x) => FunctionTerm(c,x)
      case c ~ _       => Constant(c)
    }

  def system_term: Parser[E] =
    atomic_system_word ~ opt(elem(LeftParenthesis) ~> arguments <~ elem(RightParenthesis)) ^^ {
      case c ~ Some(x) => FunctionTerm(c,x)
      case c ~ _       => Constant(c)
    }

  def arguments: Parser[List[E]] = rep1sep(term, elem(Comma))

  def conditional_term: Parser[E] =
    (acceptIf(x => x.isInstanceOf[DollarWord] && x.chars.equals("$ite_t"))(_ => "Error in Conditional Term") ~ elem(LeftParenthesis)) ~>
      tff_logic_formula ~ elem(Comma) ~ term ~ elem(Comma) ~ term <~ elem(RightParenthesis) ^^ {
      case formula ~ _ ~ thn ~ _ ~ els => ConditionalTerm(formula,thn,els)
    }

  // TODO: let terms currently do not accept sequents in the expanssion of formulas.
  def let_term : Parser[E] = failure("Let expressions are not supported")


  def atomic_defined_word: Parser[String] = elem("Dollar word", _.isInstanceOf[DollarWord]) ^^ {_.chars}
  def atomic_system_word: Parser[String] = elem("Dollar Dollar word", _.isInstanceOf[DollarDollarWord]) ^^ {_.chars}

  def file_name: Parser[String] = elem("single quoted", _.isInstanceOf[SingleQuoted]) ^^ {_.chars}



  // First-order atoms
  def atomic_formula: Parser[E] =
    plain_atomic_formula ||| defined_plain_formula ||| defined_infix_formula ||| system_atomic_formula

  def plain_atomic_formula: Parser[E] = plain_term
  def defined_plain_formula: Parser[E] = defined_plain_term
  def defined_infix_formula: Parser[E] =
    term ~ elem(Equals) ~ term ^^ {
      case t1 ~ _ ~ t2 => FormulaEquality(t1,t2)
    }
  def system_atomic_formula: Parser[E] = system_term



  // We finally have the different  formula parsers
  ////////////////////////////////////////////////////
  // TPI Formulas
  ////////////////////////////////////////////////////
  def tpi_formula : Parser[RepresentedFormula] = fof_formula

  ////////////////////////////////////////////////////
  // FOF Formulas
  ////////////////////////////////////////////////////
  def fof_formula : Parser[RepresentedFormula] = (
    fof_logic_formula ^^ {SimpleFormula(_)}
      | fof_sequent
    )

  def fof_logic_formula : Parser[E] = fof_binary_formula ||| fof_unitary_formula

  def fof_binary_formula: Parser[E] = fof_binary_non_assoc ||| fof_binary_assoc
  def fof_binary_non_assoc: Parser[E] = fof_unitary_formula ~ binary_connective ~ fof_unitary_formula ^^ {
    case left ~ Leftrightarrow      ~ right => Equivalence(left,right)
    case left ~ Rightarrow          ~ right => Imp(left,right)
    case left ~ Leftarrow           ~ right => Imp(right,left) // NOTE THE REVERSED PARAMETERS
    case left ~ Leftrighttildearrow ~ right => Neg(Equivalence(left,right))
    case left ~ TildePipe           ~ right => Neg(Or(left,right))
    case left ~ TildeAmpersand      ~ right => Neg(And(left,right))
  }

  def fof_binary_assoc: Parser[E] = fof_or_formula | fof_and_formula

  lazy val fof_or_formula: PackratParser[E] = (
    fof_unitary_formula ~ elem(VLine) ~ fof_unitary_formula  ^^ {case left ~ _ ~ right => Or(left,right)}
      ||| fof_or_formula ~ elem(VLine) ~ fof_unitary_formula ^^ {case left ~ _ ~ right => Or(left,right)}
    )

  lazy val fof_and_formula: PackratParser[E] = (
    fof_unitary_formula ~ elem(Ampersand) ~ fof_unitary_formula   ^^ {case left ~ _ ~ right => And(left,right)}
      ||| fof_and_formula ~ elem(Ampersand) ~ fof_unitary_formula ^^ {case left ~ _ ~ right => And(left,right)}
    )

  def fof_unitary_formula: Parser[E] = (
    elem(LeftParenthesis) ~> fof_logic_formula <~ elem(RightParenthesis)
      | fof_quantified_formula
      | fof_unary_formula
      | atomic_formula
    )

  def fof_quantified_formula: Parser[E] =
    fol_quantifier ~ elem(LeftBracket) ~ rep1sep(variable^^{Variable(_).asInstanceOf[Var]},elem(Comma)) ~ elem(RightBracket) ~ elem(Colon) ~ fof_unitary_formula ^^ {
      case Exclamationmark ~ _ ~ vars ~ _ ~ _ ~ matrix => All(vars,matrix)
      case Questionmark    ~ _ ~ vars ~ _ ~ _ ~ matrix => Ex(vars,matrix)
    }

  def fol_quantifier: Parser[Token] = elem(Exclamationmark) | elem(Questionmark)
  def binary_connective: Parser[Token] = (
    elem(Leftrightarrow)
      | elem(Rightarrow)
      | elem(Leftarrow)
      | elem(Leftrighttildearrow)
      | elem(TildePipe)
      | elem(TildeAmpersand)
    )

  def fof_unary_formula: Parser[E] = (
    unary_connective ~ fof_unitary_formula ^^ {case Tilde ~ formula => Neg(formula)}
      | fol_infix_unary                        ^^ {case left  ~ right   => Neg(FormulaEquality(left,right))}
    )

  def unary_connective: Parser[Token] = elem(Tilde)


  def fol_infix_unary: Parser[E ~ E] =
    term ~ elem(NotEquals) ~ term ^^ {
      case l ~ _ ~ r => this.~(l,r)
    }

  def thfUnaryConnective: Parser[Any] = (
    unary_connective
      | elem(Exclamationmark) ~ elem(Exclamationmark)
      | elem(Questionmark) ~ elem(Questionmark)
    )

  def fof_sequent: Parser[RepresentedFormula] = (
    fof_tuple ~ gentzen_arrow ~ fof_tuple ^^ {case t1 ~ _ ~ t2 => SimpleSequent(t1,t2)}
      ||| elem(LeftParenthesis) ~> fof_sequent <~ elem(RightParenthesis)
    )

  def gentzen_arrow: Parser[String] = elem(Minus) ~ elem(Minus) ~ elem(Rightarrow) ^^ {_ => ""}

  def fof_tuple: Parser[List[E]] =
    elem(LeftBracket) ~> repsep(fof_logic_formula, elem(Comma)) <~ elem(RightBracket)

  ////////////////////////////////////////////////////
  // CNF Formulas
  ////////////////////////////////////////////////////
  def cnf_formula : Parser[RepresentedFormula] = (
    elem(LeftParenthesis) ~> disjunction <~ elem(RightParenthesis)
      ||| disjunction
    ) ^^ {case (ant,suc) => SimpleSequent(ant,suc)}


  lazy val disjunction: PackratParser[(List[E],List[E])] = (
    literal                                   ^^ { case Left(l)  => (List(l) , List())
                                                   case Right(l) => (List() , List(l))
                                                 }
      ||| disjunction ~ elem(VLine) ~ literal ^^ { case (ant, suc) ~ _ ~ Left(l)  => (ant ++ List(l) , suc)
                                                   case (ant, suc) ~ _ ~ Right(l) => (ant , suc ++ List(l))
                                                 }
    )

  def literal: Parser[Either[E,E]] = (
    atomic_formula                      ^^ {Right(_)}
      ||| elem(Tilde) ~> atomic_formula ^^ {Left(_)}
      ||| fol_infix_unary               ^^ {case left ~ right => Left(FormulaEquality(left,right))}
    )

  ////////////////////////////////////////////////////
  // TFF Formulas
  ////////////////////////////////////////////////////
  def tff_formula : Parser[RepresentedFormula] = failure("tff_formula parser not implemented")

  def tff_logic_formula : Parser[E] = failure("tff_logic_formula parser not implemented")
  ////////////////////////////////////////////////////
  // THF Formulas
  ////////////////////////////////////////////////////
  def thf_formula : Parser[RepresentedFormula] = failure("thf_formula parser not implemented")

  def thf_logic_formula : Parser[E] = failure("thf_logic_formula parser not implemented")
}

trait ProblemParserTPTP
extends BaseParserTPTP {
}

trait ProofParserTPTP
extends BaseParserTPTP {

  private var varMap   = new MMap[String,E]
  private var exprMap  = new MMap[String,E]
  private var proofMap = new MMap[String,Node]

  def reset() : Unit = {
    varMap.clear()
    exprMap.clear()
    proofMap.clear()
  }

  //returns the actual proof
  def proof: Parser[Proof[Node]] = TPTP_file ^^ { p => if (p.nonEmpty) p.last
                                                       else throw new UnexpectedEmptyTPTPFileException                                       }

  def read(filename: String) : Proof[Node] = {
    val p : Proof[Node] = parse(filename,proof) match {
      case Success(p2,_)      => p2
      case Error(message,_)   => throw new Exception("Error: " + message)
      case Failure(message,_) => throw new Exception("Failure: " + message)
    }
    reset()
    p
  }



  def TPTP_file  : Parser[List[Proof[Node]]] = ???
  def TPTP_input : Parser[Proof[Node]] = ???

}

