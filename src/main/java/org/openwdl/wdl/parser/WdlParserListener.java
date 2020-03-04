// Generated from WdlParser.g4 by ANTLR 4.8
package org.openwdl.wdl.parser;
import org.antlr.v4.runtime.tree.ParseTreeListener;

/**
 * This interface defines a complete listener for a parse tree produced by
 * {@link WdlParser}.
 */
public interface WdlParserListener extends ParseTreeListener {
	/**
	 * Enter a parse tree produced by {@link WdlParser#map_type}.
	 * @param ctx the parse tree
	 */
	void enterMap_type(WdlParser.Map_typeContext ctx);
	/**
	 * Exit a parse tree produced by {@link WdlParser#map_type}.
	 * @param ctx the parse tree
	 */
	void exitMap_type(WdlParser.Map_typeContext ctx);
	/**
	 * Enter a parse tree produced by {@link WdlParser#array_type}.
	 * @param ctx the parse tree
	 */
	void enterArray_type(WdlParser.Array_typeContext ctx);
	/**
	 * Exit a parse tree produced by {@link WdlParser#array_type}.
	 * @param ctx the parse tree
	 */
	void exitArray_type(WdlParser.Array_typeContext ctx);
	/**
	 * Enter a parse tree produced by {@link WdlParser#pair_type}.
	 * @param ctx the parse tree
	 */
	void enterPair_type(WdlParser.Pair_typeContext ctx);
	/**
	 * Exit a parse tree produced by {@link WdlParser#pair_type}.
	 * @param ctx the parse tree
	 */
	void exitPair_type(WdlParser.Pair_typeContext ctx);
	/**
	 * Enter a parse tree produced by {@link WdlParser#type_base}.
	 * @param ctx the parse tree
	 */
	void enterType_base(WdlParser.Type_baseContext ctx);
	/**
	 * Exit a parse tree produced by {@link WdlParser#type_base}.
	 * @param ctx the parse tree
	 */
	void exitType_base(WdlParser.Type_baseContext ctx);
	/**
	 * Enter a parse tree produced by {@link WdlParser#wdl_type}.
	 * @param ctx the parse tree
	 */
	void enterWdl_type(WdlParser.Wdl_typeContext ctx);
	/**
	 * Exit a parse tree produced by {@link WdlParser#wdl_type}.
	 * @param ctx the parse tree
	 */
	void exitWdl_type(WdlParser.Wdl_typeContext ctx);
	/**
	 * Enter a parse tree produced by {@link WdlParser#unboud_decls}.
	 * @param ctx the parse tree
	 */
	void enterUnboud_decls(WdlParser.Unboud_declsContext ctx);
	/**
	 * Exit a parse tree produced by {@link WdlParser#unboud_decls}.
	 * @param ctx the parse tree
	 */
	void exitUnboud_decls(WdlParser.Unboud_declsContext ctx);
	/**
	 * Enter a parse tree produced by {@link WdlParser#bound_decls}.
	 * @param ctx the parse tree
	 */
	void enterBound_decls(WdlParser.Bound_declsContext ctx);
	/**
	 * Exit a parse tree produced by {@link WdlParser#bound_decls}.
	 * @param ctx the parse tree
	 */
	void exitBound_decls(WdlParser.Bound_declsContext ctx);
	/**
	 * Enter a parse tree produced by {@link WdlParser#any_decls}.
	 * @param ctx the parse tree
	 */
	void enterAny_decls(WdlParser.Any_declsContext ctx);
	/**
	 * Exit a parse tree produced by {@link WdlParser#any_decls}.
	 * @param ctx the parse tree
	 */
	void exitAny_decls(WdlParser.Any_declsContext ctx);
	/**
	 * Enter a parse tree produced by {@link WdlParser#number}.
	 * @param ctx the parse tree
	 */
	void enterNumber(WdlParser.NumberContext ctx);
	/**
	 * Exit a parse tree produced by {@link WdlParser#number}.
	 * @param ctx the parse tree
	 */
	void exitNumber(WdlParser.NumberContext ctx);
	/**
	 * Enter a parse tree produced by {@link WdlParser#expression_placeholder_option}.
	 * @param ctx the parse tree
	 */
	void enterExpression_placeholder_option(WdlParser.Expression_placeholder_optionContext ctx);
	/**
	 * Exit a parse tree produced by {@link WdlParser#expression_placeholder_option}.
	 * @param ctx the parse tree
	 */
	void exitExpression_placeholder_option(WdlParser.Expression_placeholder_optionContext ctx);
	/**
	 * Enter a parse tree produced by {@link WdlParser#dquote_string}.
	 * @param ctx the parse tree
	 */
	void enterDquote_string(WdlParser.Dquote_stringContext ctx);
	/**
	 * Exit a parse tree produced by {@link WdlParser#dquote_string}.
	 * @param ctx the parse tree
	 */
	void exitDquote_string(WdlParser.Dquote_stringContext ctx);
	/**
	 * Enter a parse tree produced by {@link WdlParser#squote_string}.
	 * @param ctx the parse tree
	 */
	void enterSquote_string(WdlParser.Squote_stringContext ctx);
	/**
	 * Exit a parse tree produced by {@link WdlParser#squote_string}.
	 * @param ctx the parse tree
	 */
	void exitSquote_string(WdlParser.Squote_stringContext ctx);
	/**
	 * Enter a parse tree produced by {@link WdlParser#string}.
	 * @param ctx the parse tree
	 */
	void enterString(WdlParser.StringContext ctx);
	/**
	 * Exit a parse tree produced by {@link WdlParser#string}.
	 * @param ctx the parse tree
	 */
	void exitString(WdlParser.StringContext ctx);
	/**
	 * Enter a parse tree produced by {@link WdlParser#primitive_literal}.
	 * @param ctx the parse tree
	 */
	void enterPrimitive_literal(WdlParser.Primitive_literalContext ctx);
	/**
	 * Exit a parse tree produced by {@link WdlParser#primitive_literal}.
	 * @param ctx the parse tree
	 */
	void exitPrimitive_literal(WdlParser.Primitive_literalContext ctx);
	/**
	 * Enter a parse tree produced by {@link WdlParser#expr}.
	 * @param ctx the parse tree
	 */
	void enterExpr(WdlParser.ExprContext ctx);
	/**
	 * Exit a parse tree produced by {@link WdlParser#expr}.
	 * @param ctx the parse tree
	 */
	void exitExpr(WdlParser.ExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code infix0}
	 * labeled alternative in {@link WdlParser#expr_infix}.
	 * @param ctx the parse tree
	 */
	void enterInfix0(WdlParser.Infix0Context ctx);
	/**
	 * Exit a parse tree produced by the {@code infix0}
	 * labeled alternative in {@link WdlParser#expr_infix}.
	 * @param ctx the parse tree
	 */
	void exitInfix0(WdlParser.Infix0Context ctx);
	/**
	 * Enter a parse tree produced by the {@code infix1}
	 * labeled alternative in {@link WdlParser#expr_infix0}.
	 * @param ctx the parse tree
	 */
	void enterInfix1(WdlParser.Infix1Context ctx);
	/**
	 * Exit a parse tree produced by the {@code infix1}
	 * labeled alternative in {@link WdlParser#expr_infix0}.
	 * @param ctx the parse tree
	 */
	void exitInfix1(WdlParser.Infix1Context ctx);
	/**
	 * Enter a parse tree produced by the {@code lor}
	 * labeled alternative in {@link WdlParser#expr_infix0}.
	 * @param ctx the parse tree
	 */
	void enterLor(WdlParser.LorContext ctx);
	/**
	 * Exit a parse tree produced by the {@code lor}
	 * labeled alternative in {@link WdlParser#expr_infix0}.
	 * @param ctx the parse tree
	 */
	void exitLor(WdlParser.LorContext ctx);
	/**
	 * Enter a parse tree produced by the {@code infix2}
	 * labeled alternative in {@link WdlParser#expr_infix1}.
	 * @param ctx the parse tree
	 */
	void enterInfix2(WdlParser.Infix2Context ctx);
	/**
	 * Exit a parse tree produced by the {@code infix2}
	 * labeled alternative in {@link WdlParser#expr_infix1}.
	 * @param ctx the parse tree
	 */
	void exitInfix2(WdlParser.Infix2Context ctx);
	/**
	 * Enter a parse tree produced by the {@code land}
	 * labeled alternative in {@link WdlParser#expr_infix1}.
	 * @param ctx the parse tree
	 */
	void enterLand(WdlParser.LandContext ctx);
	/**
	 * Exit a parse tree produced by the {@code land}
	 * labeled alternative in {@link WdlParser#expr_infix1}.
	 * @param ctx the parse tree
	 */
	void exitLand(WdlParser.LandContext ctx);
	/**
	 * Enter a parse tree produced by the {@code eqeq}
	 * labeled alternative in {@link WdlParser#expr_infix2}.
	 * @param ctx the parse tree
	 */
	void enterEqeq(WdlParser.EqeqContext ctx);
	/**
	 * Exit a parse tree produced by the {@code eqeq}
	 * labeled alternative in {@link WdlParser#expr_infix2}.
	 * @param ctx the parse tree
	 */
	void exitEqeq(WdlParser.EqeqContext ctx);
	/**
	 * Enter a parse tree produced by the {@code lt}
	 * labeled alternative in {@link WdlParser#expr_infix2}.
	 * @param ctx the parse tree
	 */
	void enterLt(WdlParser.LtContext ctx);
	/**
	 * Exit a parse tree produced by the {@code lt}
	 * labeled alternative in {@link WdlParser#expr_infix2}.
	 * @param ctx the parse tree
	 */
	void exitLt(WdlParser.LtContext ctx);
	/**
	 * Enter a parse tree produced by the {@code infix3}
	 * labeled alternative in {@link WdlParser#expr_infix2}.
	 * @param ctx the parse tree
	 */
	void enterInfix3(WdlParser.Infix3Context ctx);
	/**
	 * Exit a parse tree produced by the {@code infix3}
	 * labeled alternative in {@link WdlParser#expr_infix2}.
	 * @param ctx the parse tree
	 */
	void exitInfix3(WdlParser.Infix3Context ctx);
	/**
	 * Enter a parse tree produced by the {@code gte}
	 * labeled alternative in {@link WdlParser#expr_infix2}.
	 * @param ctx the parse tree
	 */
	void enterGte(WdlParser.GteContext ctx);
	/**
	 * Exit a parse tree produced by the {@code gte}
	 * labeled alternative in {@link WdlParser#expr_infix2}.
	 * @param ctx the parse tree
	 */
	void exitGte(WdlParser.GteContext ctx);
	/**
	 * Enter a parse tree produced by the {@code neq}
	 * labeled alternative in {@link WdlParser#expr_infix2}.
	 * @param ctx the parse tree
	 */
	void enterNeq(WdlParser.NeqContext ctx);
	/**
	 * Exit a parse tree produced by the {@code neq}
	 * labeled alternative in {@link WdlParser#expr_infix2}.
	 * @param ctx the parse tree
	 */
	void exitNeq(WdlParser.NeqContext ctx);
	/**
	 * Enter a parse tree produced by the {@code lte}
	 * labeled alternative in {@link WdlParser#expr_infix2}.
	 * @param ctx the parse tree
	 */
	void enterLte(WdlParser.LteContext ctx);
	/**
	 * Exit a parse tree produced by the {@code lte}
	 * labeled alternative in {@link WdlParser#expr_infix2}.
	 * @param ctx the parse tree
	 */
	void exitLte(WdlParser.LteContext ctx);
	/**
	 * Enter a parse tree produced by the {@code gt}
	 * labeled alternative in {@link WdlParser#expr_infix2}.
	 * @param ctx the parse tree
	 */
	void enterGt(WdlParser.GtContext ctx);
	/**
	 * Exit a parse tree produced by the {@code gt}
	 * labeled alternative in {@link WdlParser#expr_infix2}.
	 * @param ctx the parse tree
	 */
	void exitGt(WdlParser.GtContext ctx);
	/**
	 * Enter a parse tree produced by the {@code add}
	 * labeled alternative in {@link WdlParser#expr_infix3}.
	 * @param ctx the parse tree
	 */
	void enterAdd(WdlParser.AddContext ctx);
	/**
	 * Exit a parse tree produced by the {@code add}
	 * labeled alternative in {@link WdlParser#expr_infix3}.
	 * @param ctx the parse tree
	 */
	void exitAdd(WdlParser.AddContext ctx);
	/**
	 * Enter a parse tree produced by the {@code sub}
	 * labeled alternative in {@link WdlParser#expr_infix3}.
	 * @param ctx the parse tree
	 */
	void enterSub(WdlParser.SubContext ctx);
	/**
	 * Exit a parse tree produced by the {@code sub}
	 * labeled alternative in {@link WdlParser#expr_infix3}.
	 * @param ctx the parse tree
	 */
	void exitSub(WdlParser.SubContext ctx);
	/**
	 * Enter a parse tree produced by the {@code infix4}
	 * labeled alternative in {@link WdlParser#expr_infix3}.
	 * @param ctx the parse tree
	 */
	void enterInfix4(WdlParser.Infix4Context ctx);
	/**
	 * Exit a parse tree produced by the {@code infix4}
	 * labeled alternative in {@link WdlParser#expr_infix3}.
	 * @param ctx the parse tree
	 */
	void exitInfix4(WdlParser.Infix4Context ctx);
	/**
	 * Enter a parse tree produced by the {@code mod}
	 * labeled alternative in {@link WdlParser#expr_infix4}.
	 * @param ctx the parse tree
	 */
	void enterMod(WdlParser.ModContext ctx);
	/**
	 * Exit a parse tree produced by the {@code mod}
	 * labeled alternative in {@link WdlParser#expr_infix4}.
	 * @param ctx the parse tree
	 */
	void exitMod(WdlParser.ModContext ctx);
	/**
	 * Enter a parse tree produced by the {@code mul}
	 * labeled alternative in {@link WdlParser#expr_infix4}.
	 * @param ctx the parse tree
	 */
	void enterMul(WdlParser.MulContext ctx);
	/**
	 * Exit a parse tree produced by the {@code mul}
	 * labeled alternative in {@link WdlParser#expr_infix4}.
	 * @param ctx the parse tree
	 */
	void exitMul(WdlParser.MulContext ctx);
	/**
	 * Enter a parse tree produced by the {@code divide}
	 * labeled alternative in {@link WdlParser#expr_infix4}.
	 * @param ctx the parse tree
	 */
	void enterDivide(WdlParser.DivideContext ctx);
	/**
	 * Exit a parse tree produced by the {@code divide}
	 * labeled alternative in {@link WdlParser#expr_infix4}.
	 * @param ctx the parse tree
	 */
	void exitDivide(WdlParser.DivideContext ctx);
	/**
	 * Enter a parse tree produced by the {@code infix5}
	 * labeled alternative in {@link WdlParser#expr_infix4}.
	 * @param ctx the parse tree
	 */
	void enterInfix5(WdlParser.Infix5Context ctx);
	/**
	 * Exit a parse tree produced by the {@code infix5}
	 * labeled alternative in {@link WdlParser#expr_infix4}.
	 * @param ctx the parse tree
	 */
	void exitInfix5(WdlParser.Infix5Context ctx);
	/**
	 * Enter a parse tree produced by {@link WdlParser#expr_infix5}.
	 * @param ctx the parse tree
	 */
	void enterExpr_infix5(WdlParser.Expr_infix5Context ctx);
	/**
	 * Exit a parse tree produced by {@link WdlParser#expr_infix5}.
	 * @param ctx the parse tree
	 */
	void exitExpr_infix5(WdlParser.Expr_infix5Context ctx);
	/**
	 * Enter a parse tree produced by the {@code pair_literal}
	 * labeled alternative in {@link WdlParser#expr_core}.
	 * @param ctx the parse tree
	 */
	void enterPair_literal(WdlParser.Pair_literalContext ctx);
	/**
	 * Exit a parse tree produced by the {@code pair_literal}
	 * labeled alternative in {@link WdlParser#expr_core}.
	 * @param ctx the parse tree
	 */
	void exitPair_literal(WdlParser.Pair_literalContext ctx);
	/**
	 * Enter a parse tree produced by the {@code apply}
	 * labeled alternative in {@link WdlParser#expr_core}.
	 * @param ctx the parse tree
	 */
	void enterApply(WdlParser.ApplyContext ctx);
	/**
	 * Exit a parse tree produced by the {@code apply}
	 * labeled alternative in {@link WdlParser#expr_core}.
	 * @param ctx the parse tree
	 */
	void exitApply(WdlParser.ApplyContext ctx);
	/**
	 * Enter a parse tree produced by the {@code expression_group}
	 * labeled alternative in {@link WdlParser#expr_core}.
	 * @param ctx the parse tree
	 */
	void enterExpression_group(WdlParser.Expression_groupContext ctx);
	/**
	 * Exit a parse tree produced by the {@code expression_group}
	 * labeled alternative in {@link WdlParser#expr_core}.
	 * @param ctx the parse tree
	 */
	void exitExpression_group(WdlParser.Expression_groupContext ctx);
	/**
	 * Enter a parse tree produced by the {@code primitives}
	 * labeled alternative in {@link WdlParser#expr_core}.
	 * @param ctx the parse tree
	 */
	void enterPrimitives(WdlParser.PrimitivesContext ctx);
	/**
	 * Exit a parse tree produced by the {@code primitives}
	 * labeled alternative in {@link WdlParser#expr_core}.
	 * @param ctx the parse tree
	 */
	void exitPrimitives(WdlParser.PrimitivesContext ctx);
	/**
	 * Enter a parse tree produced by the {@code left_name}
	 * labeled alternative in {@link WdlParser#expr_core}.
	 * @param ctx the parse tree
	 */
	void enterLeft_name(WdlParser.Left_nameContext ctx);
	/**
	 * Exit a parse tree produced by the {@code left_name}
	 * labeled alternative in {@link WdlParser#expr_core}.
	 * @param ctx the parse tree
	 */
	void exitLeft_name(WdlParser.Left_nameContext ctx);
	/**
	 * Enter a parse tree produced by the {@code at}
	 * labeled alternative in {@link WdlParser#expr_core}.
	 * @param ctx the parse tree
	 */
	void enterAt(WdlParser.AtContext ctx);
	/**
	 * Exit a parse tree produced by the {@code at}
	 * labeled alternative in {@link WdlParser#expr_core}.
	 * @param ctx the parse tree
	 */
	void exitAt(WdlParser.AtContext ctx);
	/**
	 * Enter a parse tree produced by the {@code negate}
	 * labeled alternative in {@link WdlParser#expr_core}.
	 * @param ctx the parse tree
	 */
	void enterNegate(WdlParser.NegateContext ctx);
	/**
	 * Exit a parse tree produced by the {@code negate}
	 * labeled alternative in {@link WdlParser#expr_core}.
	 * @param ctx the parse tree
	 */
	void exitNegate(WdlParser.NegateContext ctx);
	/**
	 * Enter a parse tree produced by the {@code unirarysigned}
	 * labeled alternative in {@link WdlParser#expr_core}.
	 * @param ctx the parse tree
	 */
	void enterUnirarysigned(WdlParser.UnirarysignedContext ctx);
	/**
	 * Exit a parse tree produced by the {@code unirarysigned}
	 * labeled alternative in {@link WdlParser#expr_core}.
	 * @param ctx the parse tree
	 */
	void exitUnirarysigned(WdlParser.UnirarysignedContext ctx);
	/**
	 * Enter a parse tree produced by the {@code map_literal}
	 * labeled alternative in {@link WdlParser#expr_core}.
	 * @param ctx the parse tree
	 */
	void enterMap_literal(WdlParser.Map_literalContext ctx);
	/**
	 * Exit a parse tree produced by the {@code map_literal}
	 * labeled alternative in {@link WdlParser#expr_core}.
	 * @param ctx the parse tree
	 */
	void exitMap_literal(WdlParser.Map_literalContext ctx);
	/**
	 * Enter a parse tree produced by the {@code ifthenelse}
	 * labeled alternative in {@link WdlParser#expr_core}.
	 * @param ctx the parse tree
	 */
	void enterIfthenelse(WdlParser.IfthenelseContext ctx);
	/**
	 * Exit a parse tree produced by the {@code ifthenelse}
	 * labeled alternative in {@link WdlParser#expr_core}.
	 * @param ctx the parse tree
	 */
	void exitIfthenelse(WdlParser.IfthenelseContext ctx);
	/**
	 * Enter a parse tree produced by the {@code get_name}
	 * labeled alternative in {@link WdlParser#expr_core}.
	 * @param ctx the parse tree
	 */
	void enterGet_name(WdlParser.Get_nameContext ctx);
	/**
	 * Exit a parse tree produced by the {@code get_name}
	 * labeled alternative in {@link WdlParser#expr_core}.
	 * @param ctx the parse tree
	 */
	void exitGet_name(WdlParser.Get_nameContext ctx);
	/**
	 * Enter a parse tree produced by the {@code object_literal}
	 * labeled alternative in {@link WdlParser#expr_core}.
	 * @param ctx the parse tree
	 */
	void enterObject_literal(WdlParser.Object_literalContext ctx);
	/**
	 * Exit a parse tree produced by the {@code object_literal}
	 * labeled alternative in {@link WdlParser#expr_core}.
	 * @param ctx the parse tree
	 */
	void exitObject_literal(WdlParser.Object_literalContext ctx);
	/**
	 * Enter a parse tree produced by the {@code array_literal}
	 * labeled alternative in {@link WdlParser#expr_core}.
	 * @param ctx the parse tree
	 */
	void enterArray_literal(WdlParser.Array_literalContext ctx);
	/**
	 * Exit a parse tree produced by the {@code array_literal}
	 * labeled alternative in {@link WdlParser#expr_core}.
	 * @param ctx the parse tree
	 */
	void exitArray_literal(WdlParser.Array_literalContext ctx);
	/**
	 * Enter a parse tree produced by {@link WdlParser#version}.
	 * @param ctx the parse tree
	 */
	void enterVersion(WdlParser.VersionContext ctx);
	/**
	 * Exit a parse tree produced by {@link WdlParser#version}.
	 * @param ctx the parse tree
	 */
	void exitVersion(WdlParser.VersionContext ctx);
	/**
	 * Enter a parse tree produced by {@link WdlParser#import_alias}.
	 * @param ctx the parse tree
	 */
	void enterImport_alias(WdlParser.Import_aliasContext ctx);
	/**
	 * Exit a parse tree produced by {@link WdlParser#import_alias}.
	 * @param ctx the parse tree
	 */
	void exitImport_alias(WdlParser.Import_aliasContext ctx);
	/**
	 * Enter a parse tree produced by {@link WdlParser#import_doc}.
	 * @param ctx the parse tree
	 */
	void enterImport_doc(WdlParser.Import_docContext ctx);
	/**
	 * Exit a parse tree produced by {@link WdlParser#import_doc}.
	 * @param ctx the parse tree
	 */
	void exitImport_doc(WdlParser.Import_docContext ctx);
	/**
	 * Enter a parse tree produced by {@link WdlParser#struct}.
	 * @param ctx the parse tree
	 */
	void enterStruct(WdlParser.StructContext ctx);
	/**
	 * Exit a parse tree produced by {@link WdlParser#struct}.
	 * @param ctx the parse tree
	 */
	void exitStruct(WdlParser.StructContext ctx);
	/**
	 * Enter a parse tree produced by {@link WdlParser#meta_kv}.
	 * @param ctx the parse tree
	 */
	void enterMeta_kv(WdlParser.Meta_kvContext ctx);
	/**
	 * Exit a parse tree produced by {@link WdlParser#meta_kv}.
	 * @param ctx the parse tree
	 */
	void exitMeta_kv(WdlParser.Meta_kvContext ctx);
	/**
	 * Enter a parse tree produced by the {@code parameter_meta}
	 * labeled alternative in {@link WdlParser#meta_obj}.
	 * @param ctx the parse tree
	 */
	void enterParameter_meta(WdlParser.Parameter_metaContext ctx);
	/**
	 * Exit a parse tree produced by the {@code parameter_meta}
	 * labeled alternative in {@link WdlParser#meta_obj}.
	 * @param ctx the parse tree
	 */
	void exitParameter_meta(WdlParser.Parameter_metaContext ctx);
	/**
	 * Enter a parse tree produced by the {@code meta}
	 * labeled alternative in {@link WdlParser#meta_obj}.
	 * @param ctx the parse tree
	 */
	void enterMeta(WdlParser.MetaContext ctx);
	/**
	 * Exit a parse tree produced by the {@code meta}
	 * labeled alternative in {@link WdlParser#meta_obj}.
	 * @param ctx the parse tree
	 */
	void exitMeta(WdlParser.MetaContext ctx);
	/**
	 * Enter a parse tree produced by {@link WdlParser#task_runtime_kv}.
	 * @param ctx the parse tree
	 */
	void enterTask_runtime_kv(WdlParser.Task_runtime_kvContext ctx);
	/**
	 * Exit a parse tree produced by {@link WdlParser#task_runtime_kv}.
	 * @param ctx the parse tree
	 */
	void exitTask_runtime_kv(WdlParser.Task_runtime_kvContext ctx);
	/**
	 * Enter a parse tree produced by {@link WdlParser#task_runtime}.
	 * @param ctx the parse tree
	 */
	void enterTask_runtime(WdlParser.Task_runtimeContext ctx);
	/**
	 * Exit a parse tree produced by {@link WdlParser#task_runtime}.
	 * @param ctx the parse tree
	 */
	void exitTask_runtime(WdlParser.Task_runtimeContext ctx);
	/**
	 * Enter a parse tree produced by {@link WdlParser#task_input}.
	 * @param ctx the parse tree
	 */
	void enterTask_input(WdlParser.Task_inputContext ctx);
	/**
	 * Exit a parse tree produced by {@link WdlParser#task_input}.
	 * @param ctx the parse tree
	 */
	void exitTask_input(WdlParser.Task_inputContext ctx);
	/**
	 * Enter a parse tree produced by {@link WdlParser#task_output}.
	 * @param ctx the parse tree
	 */
	void enterTask_output(WdlParser.Task_outputContext ctx);
	/**
	 * Exit a parse tree produced by {@link WdlParser#task_output}.
	 * @param ctx the parse tree
	 */
	void exitTask_output(WdlParser.Task_outputContext ctx);
	/**
	 * Enter a parse tree produced by {@link WdlParser#task_command}.
	 * @param ctx the parse tree
	 */
	void enterTask_command(WdlParser.Task_commandContext ctx);
	/**
	 * Exit a parse tree produced by {@link WdlParser#task_command}.
	 * @param ctx the parse tree
	 */
	void exitTask_command(WdlParser.Task_commandContext ctx);
	/**
	 * Enter a parse tree produced by {@link WdlParser#task_element}.
	 * @param ctx the parse tree
	 */
	void enterTask_element(WdlParser.Task_elementContext ctx);
	/**
	 * Exit a parse tree produced by {@link WdlParser#task_element}.
	 * @param ctx the parse tree
	 */
	void exitTask_element(WdlParser.Task_elementContext ctx);
	/**
	 * Enter a parse tree produced by {@link WdlParser#task}.
	 * @param ctx the parse tree
	 */
	void enterTask(WdlParser.TaskContext ctx);
	/**
	 * Exit a parse tree produced by {@link WdlParser#task}.
	 * @param ctx the parse tree
	 */
	void exitTask(WdlParser.TaskContext ctx);
	/**
	 * Enter a parse tree produced by {@link WdlParser#inner_workflow_element}.
	 * @param ctx the parse tree
	 */
	void enterInner_workflow_element(WdlParser.Inner_workflow_elementContext ctx);
	/**
	 * Exit a parse tree produced by {@link WdlParser#inner_workflow_element}.
	 * @param ctx the parse tree
	 */
	void exitInner_workflow_element(WdlParser.Inner_workflow_elementContext ctx);
	/**
	 * Enter a parse tree produced by {@link WdlParser#call_alias}.
	 * @param ctx the parse tree
	 */
	void enterCall_alias(WdlParser.Call_aliasContext ctx);
	/**
	 * Exit a parse tree produced by {@link WdlParser#call_alias}.
	 * @param ctx the parse tree
	 */
	void exitCall_alias(WdlParser.Call_aliasContext ctx);
	/**
	 * Enter a parse tree produced by {@link WdlParser#call_input}.
	 * @param ctx the parse tree
	 */
	void enterCall_input(WdlParser.Call_inputContext ctx);
	/**
	 * Exit a parse tree produced by {@link WdlParser#call_input}.
	 * @param ctx the parse tree
	 */
	void exitCall_input(WdlParser.Call_inputContext ctx);
	/**
	 * Enter a parse tree produced by {@link WdlParser#call_inputs}.
	 * @param ctx the parse tree
	 */
	void enterCall_inputs(WdlParser.Call_inputsContext ctx);
	/**
	 * Exit a parse tree produced by {@link WdlParser#call_inputs}.
	 * @param ctx the parse tree
	 */
	void exitCall_inputs(WdlParser.Call_inputsContext ctx);
	/**
	 * Enter a parse tree produced by {@link WdlParser#call_body}.
	 * @param ctx the parse tree
	 */
	void enterCall_body(WdlParser.Call_bodyContext ctx);
	/**
	 * Exit a parse tree produced by {@link WdlParser#call_body}.
	 * @param ctx the parse tree
	 */
	void exitCall_body(WdlParser.Call_bodyContext ctx);
	/**
	 * Enter a parse tree produced by {@link WdlParser#call}.
	 * @param ctx the parse tree
	 */
	void enterCall(WdlParser.CallContext ctx);
	/**
	 * Exit a parse tree produced by {@link WdlParser#call}.
	 * @param ctx the parse tree
	 */
	void exitCall(WdlParser.CallContext ctx);
	/**
	 * Enter a parse tree produced by {@link WdlParser#scatter}.
	 * @param ctx the parse tree
	 */
	void enterScatter(WdlParser.ScatterContext ctx);
	/**
	 * Exit a parse tree produced by {@link WdlParser#scatter}.
	 * @param ctx the parse tree
	 */
	void exitScatter(WdlParser.ScatterContext ctx);
	/**
	 * Enter a parse tree produced by {@link WdlParser#conditional}.
	 * @param ctx the parse tree
	 */
	void enterConditional(WdlParser.ConditionalContext ctx);
	/**
	 * Exit a parse tree produced by {@link WdlParser#conditional}.
	 * @param ctx the parse tree
	 */
	void exitConditional(WdlParser.ConditionalContext ctx);
	/**
	 * Enter a parse tree produced by {@link WdlParser#workflow_input}.
	 * @param ctx the parse tree
	 */
	void enterWorkflow_input(WdlParser.Workflow_inputContext ctx);
	/**
	 * Exit a parse tree produced by {@link WdlParser#workflow_input}.
	 * @param ctx the parse tree
	 */
	void exitWorkflow_input(WdlParser.Workflow_inputContext ctx);
	/**
	 * Enter a parse tree produced by {@link WdlParser#workflow_output}.
	 * @param ctx the parse tree
	 */
	void enterWorkflow_output(WdlParser.Workflow_outputContext ctx);
	/**
	 * Exit a parse tree produced by {@link WdlParser#workflow_output}.
	 * @param ctx the parse tree
	 */
	void exitWorkflow_output(WdlParser.Workflow_outputContext ctx);
	/**
	 * Enter a parse tree produced by the {@code input}
	 * labeled alternative in {@link WdlParser#workflow_element}.
	 * @param ctx the parse tree
	 */
	void enterInput(WdlParser.InputContext ctx);
	/**
	 * Exit a parse tree produced by the {@code input}
	 * labeled alternative in {@link WdlParser#workflow_element}.
	 * @param ctx the parse tree
	 */
	void exitInput(WdlParser.InputContext ctx);
	/**
	 * Enter a parse tree produced by the {@code output}
	 * labeled alternative in {@link WdlParser#workflow_element}.
	 * @param ctx the parse tree
	 */
	void enterOutput(WdlParser.OutputContext ctx);
	/**
	 * Exit a parse tree produced by the {@code output}
	 * labeled alternative in {@link WdlParser#workflow_element}.
	 * @param ctx the parse tree
	 */
	void exitOutput(WdlParser.OutputContext ctx);
	/**
	 * Enter a parse tree produced by the {@code inner_element}
	 * labeled alternative in {@link WdlParser#workflow_element}.
	 * @param ctx the parse tree
	 */
	void enterInner_element(WdlParser.Inner_elementContext ctx);
	/**
	 * Exit a parse tree produced by the {@code inner_element}
	 * labeled alternative in {@link WdlParser#workflow_element}.
	 * @param ctx the parse tree
	 */
	void exitInner_element(WdlParser.Inner_elementContext ctx);
	/**
	 * Enter a parse tree produced by the {@code meta_element}
	 * labeled alternative in {@link WdlParser#workflow_element}.
	 * @param ctx the parse tree
	 */
	void enterMeta_element(WdlParser.Meta_elementContext ctx);
	/**
	 * Exit a parse tree produced by the {@code meta_element}
	 * labeled alternative in {@link WdlParser#workflow_element}.
	 * @param ctx the parse tree
	 */
	void exitMeta_element(WdlParser.Meta_elementContext ctx);
	/**
	 * Enter a parse tree produced by {@link WdlParser#workflow}.
	 * @param ctx the parse tree
	 */
	void enterWorkflow(WdlParser.WorkflowContext ctx);
	/**
	 * Exit a parse tree produced by {@link WdlParser#workflow}.
	 * @param ctx the parse tree
	 */
	void exitWorkflow(WdlParser.WorkflowContext ctx);
	/**
	 * Enter a parse tree produced by {@link WdlParser#document_element}.
	 * @param ctx the parse tree
	 */
	void enterDocument_element(WdlParser.Document_elementContext ctx);
	/**
	 * Exit a parse tree produced by {@link WdlParser#document_element}.
	 * @param ctx the parse tree
	 */
	void exitDocument_element(WdlParser.Document_elementContext ctx);
	/**
	 * Enter a parse tree produced by {@link WdlParser#document}.
	 * @param ctx the parse tree
	 */
	void enterDocument(WdlParser.DocumentContext ctx);
	/**
	 * Exit a parse tree produced by {@link WdlParser#document}.
	 * @param ctx the parse tree
	 */
	void exitDocument(WdlParser.DocumentContext ctx);
}