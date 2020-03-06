// Generated from WdlParser.g4 by ANTLR 4.8
package org.openwdl.wdl.parser;
import org.antlr.v4.runtime.tree.ParseTreeVisitor;

/**
 * This interface defines a complete generic visitor for a parse tree produced
 * by {@link WdlParser}.
 *
 * @param <T> The return type of the visit operation. Use {@link Void} for
 * operations with no return type.
 */
public interface WdlParserVisitor<T> extends ParseTreeVisitor<T> {
	/**
	 * Visit a parse tree produced by {@link WdlParser#map_type}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitMap_type(WdlParser.Map_typeContext ctx);
	/**
	 * Visit a parse tree produced by {@link WdlParser#array_type}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitArray_type(WdlParser.Array_typeContext ctx);
	/**
	 * Visit a parse tree produced by {@link WdlParser#pair_type}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPair_type(WdlParser.Pair_typeContext ctx);
	/**
	 * Visit a parse tree produced by {@link WdlParser#type_base}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitType_base(WdlParser.Type_baseContext ctx);
	/**
	 * Visit a parse tree produced by {@link WdlParser#wdl_type}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitWdl_type(WdlParser.Wdl_typeContext ctx);
	/**
	 * Visit a parse tree produced by {@link WdlParser#unbound_decls}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitUnbound_decls(WdlParser.Unbound_declsContext ctx);
	/**
	 * Visit a parse tree produced by {@link WdlParser#bound_decls}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBound_decls(WdlParser.Bound_declsContext ctx);
	/**
	 * Visit a parse tree produced by {@link WdlParser#any_decls}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAny_decls(WdlParser.Any_declsContext ctx);
	/**
	 * Visit a parse tree produced by {@link WdlParser#number}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNumber(WdlParser.NumberContext ctx);
	/**
	 * Visit a parse tree produced by {@link WdlParser#expression_placeholder_option}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitExpression_placeholder_option(WdlParser.Expression_placeholder_optionContext ctx);
	/**
	 * Visit a parse tree produced by {@link WdlParser#dquote_string}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDquote_string(WdlParser.Dquote_stringContext ctx);
	/**
	 * Visit a parse tree produced by {@link WdlParser#squote_string}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSquote_string(WdlParser.Squote_stringContext ctx);
	/**
	 * Visit a parse tree produced by {@link WdlParser#string}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitString(WdlParser.StringContext ctx);
	/**
	 * Visit a parse tree produced by {@link WdlParser#primitive_literal}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPrimitive_literal(WdlParser.Primitive_literalContext ctx);
	/**
	 * Visit a parse tree produced by {@link WdlParser#expr}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitExpr(WdlParser.ExprContext ctx);
	/**
	 * Visit a parse tree produced by the {@code infix0}
	 * labeled alternative in {@link WdlParser#expr_infix}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitInfix0(WdlParser.Infix0Context ctx);
	/**
	 * Visit a parse tree produced by the {@code infix1}
	 * labeled alternative in {@link WdlParser#expr_infix0}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitInfix1(WdlParser.Infix1Context ctx);
	/**
	 * Visit a parse tree produced by the {@code lor}
	 * labeled alternative in {@link WdlParser#expr_infix0}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLor(WdlParser.LorContext ctx);
	/**
	 * Visit a parse tree produced by the {@code infix2}
	 * labeled alternative in {@link WdlParser#expr_infix1}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitInfix2(WdlParser.Infix2Context ctx);
	/**
	 * Visit a parse tree produced by the {@code land}
	 * labeled alternative in {@link WdlParser#expr_infix1}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLand(WdlParser.LandContext ctx);
	/**
	 * Visit a parse tree produced by the {@code eqeq}
	 * labeled alternative in {@link WdlParser#expr_infix2}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitEqeq(WdlParser.EqeqContext ctx);
	/**
	 * Visit a parse tree produced by the {@code lt}
	 * labeled alternative in {@link WdlParser#expr_infix2}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLt(WdlParser.LtContext ctx);
	/**
	 * Visit a parse tree produced by the {@code infix3}
	 * labeled alternative in {@link WdlParser#expr_infix2}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitInfix3(WdlParser.Infix3Context ctx);
	/**
	 * Visit a parse tree produced by the {@code gte}
	 * labeled alternative in {@link WdlParser#expr_infix2}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitGte(WdlParser.GteContext ctx);
	/**
	 * Visit a parse tree produced by the {@code neq}
	 * labeled alternative in {@link WdlParser#expr_infix2}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNeq(WdlParser.NeqContext ctx);
	/**
	 * Visit a parse tree produced by the {@code lte}
	 * labeled alternative in {@link WdlParser#expr_infix2}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLte(WdlParser.LteContext ctx);
	/**
	 * Visit a parse tree produced by the {@code gt}
	 * labeled alternative in {@link WdlParser#expr_infix2}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitGt(WdlParser.GtContext ctx);
	/**
	 * Visit a parse tree produced by the {@code add}
	 * labeled alternative in {@link WdlParser#expr_infix3}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAdd(WdlParser.AddContext ctx);
	/**
	 * Visit a parse tree produced by the {@code sub}
	 * labeled alternative in {@link WdlParser#expr_infix3}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSub(WdlParser.SubContext ctx);
	/**
	 * Visit a parse tree produced by the {@code infix4}
	 * labeled alternative in {@link WdlParser#expr_infix3}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitInfix4(WdlParser.Infix4Context ctx);
	/**
	 * Visit a parse tree produced by the {@code mod}
	 * labeled alternative in {@link WdlParser#expr_infix4}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitMod(WdlParser.ModContext ctx);
	/**
	 * Visit a parse tree produced by the {@code mul}
	 * labeled alternative in {@link WdlParser#expr_infix4}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitMul(WdlParser.MulContext ctx);
	/**
	 * Visit a parse tree produced by the {@code divide}
	 * labeled alternative in {@link WdlParser#expr_infix4}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDivide(WdlParser.DivideContext ctx);
	/**
	 * Visit a parse tree produced by the {@code infix5}
	 * labeled alternative in {@link WdlParser#expr_infix4}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitInfix5(WdlParser.Infix5Context ctx);
	/**
	 * Visit a parse tree produced by {@link WdlParser#expr_infix5}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitExpr_infix5(WdlParser.Expr_infix5Context ctx);
	/**
	 * Visit a parse tree produced by the {@code pair_literal}
	 * labeled alternative in {@link WdlParser#expr_core}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPair_literal(WdlParser.Pair_literalContext ctx);
	/**
	 * Visit a parse tree produced by the {@code apply}
	 * labeled alternative in {@link WdlParser#expr_core}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitApply(WdlParser.ApplyContext ctx);
	/**
	 * Visit a parse tree produced by the {@code expression_group}
	 * labeled alternative in {@link WdlParser#expr_core}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitExpression_group(WdlParser.Expression_groupContext ctx);
	/**
	 * Visit a parse tree produced by the {@code primitives}
	 * labeled alternative in {@link WdlParser#expr_core}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPrimitives(WdlParser.PrimitivesContext ctx);
	/**
	 * Visit a parse tree produced by the {@code left_name}
	 * labeled alternative in {@link WdlParser#expr_core}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLeft_name(WdlParser.Left_nameContext ctx);
	/**
	 * Visit a parse tree produced by the {@code at}
	 * labeled alternative in {@link WdlParser#expr_core}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAt(WdlParser.AtContext ctx);
	/**
	 * Visit a parse tree produced by the {@code negate}
	 * labeled alternative in {@link WdlParser#expr_core}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNegate(WdlParser.NegateContext ctx);
	/**
	 * Visit a parse tree produced by the {@code unirarysigned}
	 * labeled alternative in {@link WdlParser#expr_core}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitUnirarysigned(WdlParser.UnirarysignedContext ctx);
	/**
	 * Visit a parse tree produced by the {@code map_literal}
	 * labeled alternative in {@link WdlParser#expr_core}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitMap_literal(WdlParser.Map_literalContext ctx);
	/**
	 * Visit a parse tree produced by the {@code ifthenelse}
	 * labeled alternative in {@link WdlParser#expr_core}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitIfthenelse(WdlParser.IfthenelseContext ctx);
	/**
	 * Visit a parse tree produced by the {@code get_name}
	 * labeled alternative in {@link WdlParser#expr_core}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitGet_name(WdlParser.Get_nameContext ctx);
	/**
	 * Visit a parse tree produced by the {@code object_literal}
	 * labeled alternative in {@link WdlParser#expr_core}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitObject_literal(WdlParser.Object_literalContext ctx);
	/**
	 * Visit a parse tree produced by the {@code array_literal}
	 * labeled alternative in {@link WdlParser#expr_core}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitArray_literal(WdlParser.Array_literalContext ctx);
	/**
	 * Visit a parse tree produced by {@link WdlParser#version}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitVersion(WdlParser.VersionContext ctx);
	/**
	 * Visit a parse tree produced by {@link WdlParser#import_alias}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitImport_alias(WdlParser.Import_aliasContext ctx);
	/**
	 * Visit a parse tree produced by {@link WdlParser#import_doc}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitImport_doc(WdlParser.Import_docContext ctx);
	/**
	 * Visit a parse tree produced by {@link WdlParser#struct}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitStruct(WdlParser.StructContext ctx);
	/**
	 * Visit a parse tree produced by {@link WdlParser#meta_kv}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitMeta_kv(WdlParser.Meta_kvContext ctx);
	/**
	 * Visit a parse tree produced by the {@code parameter_meta}
	 * labeled alternative in {@link WdlParser#meta_obj}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitParameter_meta(WdlParser.Parameter_metaContext ctx);
	/**
	 * Visit a parse tree produced by the {@code meta}
	 * labeled alternative in {@link WdlParser#meta_obj}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitMeta(WdlParser.MetaContext ctx);
	/**
	 * Visit a parse tree produced by {@link WdlParser#task_runtime_kv}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTask_runtime_kv(WdlParser.Task_runtime_kvContext ctx);
	/**
	 * Visit a parse tree produced by {@link WdlParser#task_runtime}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTask_runtime(WdlParser.Task_runtimeContext ctx);
	/**
	 * Visit a parse tree produced by {@link WdlParser#task_input}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTask_input(WdlParser.Task_inputContext ctx);
	/**
	 * Visit a parse tree produced by {@link WdlParser#task_output}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTask_output(WdlParser.Task_outputContext ctx);
	/**
	 * Visit a parse tree produced by {@link WdlParser#task_command_part}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTask_command_part(WdlParser.Task_command_partContext ctx);
	/**
	 * Visit a parse tree produced by {@link WdlParser#task_command}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTask_command(WdlParser.Task_commandContext ctx);
	/**
	 * Visit a parse tree produced by {@link WdlParser#task_element}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTask_element(WdlParser.Task_elementContext ctx);
	/**
	 * Visit a parse tree produced by {@link WdlParser#task}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTask(WdlParser.TaskContext ctx);
	/**
	 * Visit a parse tree produced by {@link WdlParser#inner_workflow_element}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitInner_workflow_element(WdlParser.Inner_workflow_elementContext ctx);
	/**
	 * Visit a parse tree produced by {@link WdlParser#call_alias}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCall_alias(WdlParser.Call_aliasContext ctx);
	/**
	 * Visit a parse tree produced by {@link WdlParser#call_input}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCall_input(WdlParser.Call_inputContext ctx);
	/**
	 * Visit a parse tree produced by {@link WdlParser#call_inputs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCall_inputs(WdlParser.Call_inputsContext ctx);
	/**
	 * Visit a parse tree produced by {@link WdlParser#call_body}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCall_body(WdlParser.Call_bodyContext ctx);
	/**
	 * Visit a parse tree produced by {@link WdlParser#call}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCall(WdlParser.CallContext ctx);
	/**
	 * Visit a parse tree produced by {@link WdlParser#scatter}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitScatter(WdlParser.ScatterContext ctx);
	/**
	 * Visit a parse tree produced by {@link WdlParser#conditional}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitConditional(WdlParser.ConditionalContext ctx);
	/**
	 * Visit a parse tree produced by {@link WdlParser#workflow_input}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitWorkflow_input(WdlParser.Workflow_inputContext ctx);
	/**
	 * Visit a parse tree produced by {@link WdlParser#workflow_output}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitWorkflow_output(WdlParser.Workflow_outputContext ctx);
	/**
	 * Visit a parse tree produced by the {@code input}
	 * labeled alternative in {@link WdlParser#workflow_element}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitInput(WdlParser.InputContext ctx);
	/**
	 * Visit a parse tree produced by the {@code output}
	 * labeled alternative in {@link WdlParser#workflow_element}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitOutput(WdlParser.OutputContext ctx);
	/**
	 * Visit a parse tree produced by the {@code inner_element}
	 * labeled alternative in {@link WdlParser#workflow_element}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitInner_element(WdlParser.Inner_elementContext ctx);
	/**
	 * Visit a parse tree produced by the {@code meta_element}
	 * labeled alternative in {@link WdlParser#workflow_element}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitMeta_element(WdlParser.Meta_elementContext ctx);
	/**
	 * Visit a parse tree produced by {@link WdlParser#workflow}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitWorkflow(WdlParser.WorkflowContext ctx);
	/**
	 * Visit a parse tree produced by {@link WdlParser#document_element}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDocument_element(WdlParser.Document_elementContext ctx);
	/**
	 * Visit a parse tree produced by {@link WdlParser#document}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDocument(WdlParser.DocumentContext ctx);
}