/*
 * This file is part of the SMT project.
 * Copyright 2010 David R. Cok
 * Created August 2010
 */
package org.smtlib.solvers;

// Items not implemented:
//   attributed expressions
//   get-values get-assignment get-proof get-unsat-core
//   some error detection and handling

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.smtlib.*;
import org.smtlib.sexpr.Sexpr;
import org.smtlib.sexpr.ISexpr;
import org.smtlib.impl.SMTExpr;
import org.smtlib.ICommand.Ideclare_fun;
import org.smtlib.ICommand.Ideclare_sort;
import org.smtlib.ICommand.Ideclare_datatypes;
import org.smtlib.ICommand.Ideclare_const;
import org.smtlib.ICommand.Idefine_fun;
import org.smtlib.ICommand.Idefine_sort;
import org.smtlib.IExpr.IAttribute;
import org.smtlib.IExpr.IFcnExpr;
import org.smtlib.IExpr.IIdentifier;
import org.smtlib.IExpr.IKeyword;
import org.smtlib.IExpr.INumeral;
import org.smtlib.IExpr.IQualifiedIdentifier;
import org.smtlib.IExpr.IStringLiteral;
import org.smtlib.IParser.ParserException;
import org.smtlib.SMT.Configuration.SMTLIB;
import org.smtlib.impl.Pos;
import org.smtlib.sexpr.Printer;

/** This class is an adapter that takes the SMT-LIB ASTs and translates them into Z3 commands */
public class Solver_z3_4_3 extends AbstractSolver implements ISolver {
	
	protected String NAME_VALUE = "z3-4.3";
	protected String AUTHORS_VALUE = "Leonardo de Moura and Nikolaj Bjorner";
	protected String VERSION_VALUE = "4.3";
	

	protected int linesOffset = 0;
	
	/** A reference to the SMT configuration */
	protected SMT.Configuration smtConfig;

	/** A reference to the SMT configuration */
	public SMT.Configuration smt() { return smtConfig; }
	
	/** The command-line arguments for launching the Z3 solver */
	protected String cmds[];
	protected String cmds_win[] = new String[]{ "", "/smt2","/in"}; 
	protected String cmds_mac[] = new String[]{ "", "-smt2","-in"}; 
	protected String cmds_unix[] = new String[]{ "", "-smt2","-in"}; 

	/** The object that interacts with external processes */
	protected SolverProcess solverProcess;
	
	/** The parser that parses responses from the solver */
	protected org.smtlib.sexpr.Parser responseParser;
	
	/** Set to true once a set-logic command has been executed */
	protected boolean logicSet = false;
	
	/** The checkSatStatus returned by check-sat, if sufficiently recent, otherwise null */
	protected /*@Nullable*/ IResponse checkSatStatus = null;
	
	@Override
	public /*@Nullable*/IResponse checkSatStatus() { return checkSatStatus; }

	/** The number of pushes less the number of pops so far */
	protected int pushesDepth = 0;
	
	/** Map that keeps current values of options */
	protected Map<String,IAttributeValue> options = new HashMap<String,IAttributeValue>();
	
	/** Creates an instance of the Z3 solver */
	public Solver_z3_4_3(SMT.Configuration smtConfig, /*@NonNull*/ String executable) {
		this.smtConfig = smtConfig;
		if (isWindows) {
			cmds = cmds_win;
		} else if (isMac) {
			cmds = cmds_mac;
		} else {
			cmds = cmds_unix;
		}
		cmds[0] = executable;
		options.putAll(smtConfig.utils.defaults);
		double timeout = smtConfig.timeout;
		if (timeout > 0) {
			List<String> args = new java.util.ArrayList<String>(cmds.length+1);
			args.addAll(Arrays.asList(cmds));
			if (isWindows) args.add("/T:" + Integer.toString((int)timeout));
			else           args.add("-T:" + Integer.toString((int)timeout));
			cmds = args.toArray(new String[args.size()]);
		}
		solverProcess = new SolverProcess(cmds,"\n",smtConfig.logfile);
		responseParser = new org.smtlib.sexpr.Parser(smt(),new Pos.Source("",null));
	}

	public IResponse sendCommand(ICommand cmd) {
		String translatedCmd = null;
		try {
			translatedCmd = translate(cmd);
			return parseResponse(solverProcess.sendAndListen(translatedCmd,"\n"));
		} catch (IOException e) {
			throw new SMT.InternalException("jSMTLIB: Error writing to solver: " + translatedCmd + " .", e);
		} catch (IVisitor.VisitorException e) {
			throw new SMT.InternalException("jSMTLIB: Error writing to solver: " + translatedCmd + " .", e);
		}
	}
	
	public IResponse sendCommand(String cmd) {
		try {
			return parseResponse(solverProcess.sendAndListen(cmd,"\n"));
		} catch (IOException e) {
			throw new SMT.InternalException("jSMTLIB: Error writing to solver: " + cmd + " .", e);
		}
	}
	

	@Override
	public IResponse start() {
		try {
			solverProcess.start(false);
			// FIXME - enable the following lines when the Z3 solver supports them
//			if (smtConfig.solverVerbosity > 0) solverProcess.sendNoListen("(set-option :verbosity ",Integer.toString(smtConfig.solverVerbosity),")");
//			if (!smtConfig.batch) solverProcess.sendNoListen("(set-option :interactive-mode true)"); // FIXME - not sure we can do this - we'll lose the feedback
			// Can't turn off printing success, or we get no feedback
			solverProcess.sendAndListen("(set-option :print-success true)\n"); // Z3 4.3.0 needs this because it mistakenly has the default for :print-success as false
			linesOffset ++; 
			//if (smtConfig.nosuccess) solverProcess.sendAndListen("(set-option :print-success false)");
			if (smtConfig.verbose != 0) smtConfig.log.logDiag("Started "+NAME_VALUE+" ");
			return smtConfig.responseFactory.success();
		} catch (Exception e) {
			throw new SMT.InternalException("jSMTLIB: Failed to start process " + cmds[0] + " . ", e);
		}
	}
	
	@Override
	public IResponse exit() {
		try {
			solverProcess.sendAndListen("(exit)\n");
			solverProcess.exit();
			if (smtConfig.verbose != 0) smtConfig.log.logDiag("Ended Z3 ");
			return successOrEmpty(smtConfig);
		} catch (IOException e) {
			throw new SMT.InternalException("jSMTLIB: Error writing to Z3 solver.", e);
		}
	}

	@Override 
	public void comment(String comment) {
		try {
			solverProcess.sendNoListen(comment);
		} catch (IOException e) {
			// FIXME;
		}
	}

	/** Translates an S-expression into Z3 syntax */
	protected String translate(IAccept sexpr) throws IVisitor.VisitorException {
		// The z3 solver uses the standard S-expression concrete syntax, but not quite
		// so we have to use our own translator
		StringWriter sw = new StringWriter();
		sexpr.accept(new Translator(sw));
		return sw.toString();
	}
	
	/** Translates an S-expression into standard SMT syntax */
	protected String translateSMT(IAccept sexpr) throws IVisitor.VisitorException {
		// The z3 solver uses the standard S-expression concrete syntax, but not quite
		StringWriter sw = new StringWriter();
		org.smtlib.sexpr.Printer.write(sw,sexpr);
		return sw.toString();
	}
	
	protected IResponse parseResponse(String response) {
		try {
			Pattern oldbv = Pattern.compile("bv([0-9]+)\\[([0-9]+)\\]");
			Matcher mm = oldbv.matcher(response);
			while (mm.find()) {
				long val = Long.parseLong(mm.group(1));
				int base = Integer.parseInt(mm.group(2));
				String bits = "";
				for (int i=0; i<base; i++) { bits = ((val&1)==0 ? "0" : "1") + bits; val = val >>> 1; }
				response = response.substring(0,mm.start()) + "#b" + bits + response.substring(mm.end(),response.length());
				mm = oldbv.matcher(response);
			}
			if (isMac && response.startsWith("success")) return smtConfig.responseFactory.success(); // FIXME - this is just to avoid a problem with the Mac Z3 implementation
			// NOTE: previously, this was 'response.contains("error")'. However, this resulted in every query using a variable name containing the string "error" to result in an error...
			if (response.startsWith("error")) {
				
				// Z3 returns an s-expr (always?)
				// FIXME - (1) the {Print} also needs {Space}; (2) err_getValueTypes.tst returns a non-error s-expr and then an error s-expr - this fails for that case
				//Pattern p = Pattern.compile("\\p{Space}*\\(\\p{Blank}*error\\p{Blank}+\"(([\\p{Space}\\p{Print}^[\\\"\\\\]]|\\\\\")*)\"\\p{Blank}*\\)\\p{Space}*");
				Pattern p = Pattern.compile("\\p{Space}*\\(\\p{Blank}*error\\p{Blank}+\"(([\\p{Print}\\p{Space}&&[^\"\\\\]]|\\\\\")*)\"\\p{Blank}*\\)");
				Matcher m = p.matcher(response);
				String concat = "";
				while (m.lookingAt()) {
					if (!concat.isEmpty()) concat = concat + "; ";
					String matched = m.group(1);
					String prefix = "line ";
					int offset = prefix.length();
					if (matched.startsWith(prefix)) {
						int k = matched.indexOf(' ',offset);
						String number = matched.substring(offset, k);
						try {
							int n = Integer.parseInt(number);
							matched = prefix + (n-linesOffset) + matched.substring(k);
						} catch (NumberFormatException e) {
							// Just continue
						}
					}
					concat = concat + matched;
					m.region(m.end(0),m.regionEnd());
				}
				if (!concat.isEmpty()) response = concat;
				throw new SMT.InternalException(response);
			}
			responseParser = new org.smtlib.sexpr.Parser(smt(),new Pos.Source(response,null));
			return responseParser.parseResponse(response);
		} catch (ParserException e) {
			throw new SMT.InternalException("jSMTLIB: ParserException while parsing response: " + response + " .", e);
		}
	}

	@Override
	public IResponse assertExpr(IExpr sexpr) {
		IResponse response;
		if (pushesDepth <= 0) {
			throw new SMT.InternalException("jSMTLIB: All assertion sets have been popped from the stack");
		}
		if (!logicSet) {
			throw new SMT.InternalException("jSMTLIB: The logic must be set before an assert command is issued");
		}
		try {
			String s = solverProcess.sendAndListen("(assert ",translate(sexpr),")\n");
			response = parseResponse(s);
			checkSatStatus = null;
		} catch (IVisitor.VisitorException e) {
			throw new SMT.InternalException("jSMTLIB: Failed to assert expression; Asserted expr:" + sexpr, e);
		} catch (IOException e) {
			throw new SMT.InternalException("jSMTLIB: Failed to assert expression; Asserted expr:" + sexpr, e);
		}
		return response;
	}
	
	@Override
	public IResponse get_assertions() {
		if (!logicSet) {
			throw new SMT.InternalException("jSMTLIB: The logic must be set before a get-assertions command is issued");
		}
		// FIXME - do we really want to call get-option here? it involves going to the solver?
		if (!smtConfig.relax && !Utils.TRUE.equals(get_option(smtConfig.exprFactory.keyword(Utils.PRODUCE_ASSERTIONS)))) {
			throw new SMT.InternalException("jSMTLIB: The get-assertions command is only valid if :interactive-mode has been enabled");
		}
		try {
			StringBuilder sb = new StringBuilder();
			String s;
			int parens = 0;
			do {
				s = solverProcess.sendAndListen("(get-assertions)\n");
				int p = -1;
				while (( p = s.indexOf('(',p+1)) != -1) parens++;
				p = -1;
				while (( p = s.indexOf(')',p+1)) != -1) parens--;
				sb.append(s.replace('\n',' ').replace("\r",""));
			} while (parens > 0);
			s = sb.toString();
			org.smtlib.sexpr.Parser p = new org.smtlib.sexpr.Parser(smtConfig,new org.smtlib.impl.Pos.Source(s,null));
			List<IExpr> exprs = new LinkedList<IExpr>();
			try {
				if (p.isLP()) {
					p.parseLP();
					while (!p.isRP() && !p.isEOD()) {
						IExpr e = p.parseExpr();
						exprs.add(e);
					}
					if (p.isRP()) {
						p.parseRP();
						if (p.isEOD()) return smtConfig.responseFactory.get_assertions_response(exprs); 
					}
				}
			} catch (Exception e ) {
				// continue - fall through
			}
			throw new SMT.InternalException("jSMTLIB: Unexpected output from the Z3 solver: " + s);
		} catch (IOException e) {
			throw new SMT.InternalException("jSMTLIB: IOException while reading Z3 reponse.", e);
		}
	}
	


	@Override
	public IResponse check_sat() {
		IResponse res;
		try {
			if (!logicSet) {
				throw new SMT.InternalException("jSMTLIB: The logic must be set before a check-sat command is issued");
			}
			String s = solverProcess.sendAndListen("(check-sat)\n");
			//smtConfig.log.logDiag("HEARD: " + s);  // FIXME - detect errors - parseResponse?
			
			if (solverProcess.isRunning(false)) {
				if (s.contains("unsat")) res = smtConfig.responseFactory.unsat();
				else if (s.contains("sat")) res = smtConfig.responseFactory.sat();
				else if (s.contains("timeout")) res = smtConfig.responseFactory.timeout();
				else res = parseResponse(s);
			} else {
				throw new SMT.InternalException("jSMTLIB: Solver has unexpectedly terminated");
			}

			checkSatStatus = res;
		} catch (IOException e) {
			throw new SMT.InternalException("jSMTLIB: Failed to check-sat.", e);
		}
		return res;
	}
	
	@Override
	public IResponse reset() {
		logicSet = false;
	    return sendCommand("(reset)");
	}

	@Override
	public IResponse reset_assertions() {
	    return sendCommand("(reset-assertions)");
	}

	@Override
	public IResponse pop(int number) {
		if (!logicSet) {
			throw new SMT.InternalException("jSMTLIB: The logic must be set before a pop command is issued");
		}
		if (number < 0) throw new SMT.InternalException("Internal bug: A pop command called with a negative argument: " + number);
		if (number > pushesDepth) throw new SMT.InternalException("jSMTLIB: The argument to a pop command is too large: " + number + " vs. a maximum of " + (pushesDepth));
		if (number == 0) return  successOrEmpty(smtConfig);
		try {
			checkSatStatus = null;
			pushesDepth -= number;
			return parseResponse(solverProcess.sendAndListen("(pop ",Integer.toString(number),")\n"));
		} catch (IOException e) {
			throw new SMT.InternalException("jSMTLIB: Error writing to Z3 solver.", e);
		}
	}

	@Override
	public IResponse push(int number) {
		if (!logicSet) {
			throw new SMT.InternalException("jSMTLIB: The logic must be set before a push command is issued");
		}
		if (number < 0) throw new SMT.InternalException("Internal bug: A push command called with a negative argument: " + number);
		checkSatStatus = null;
		if (number == 0) return smtConfig.responseFactory.success();
		try {
			pushesDepth += number;
			IResponse r = parseResponse(solverProcess.sendAndListen("(push ",Integer.toString(number),")\n"));
			// FIXME - actually only see this problem on Linux
			if (r.isError() && !isWindows) return successOrEmpty(smtConfig);
			return r;
		} catch (Exception e) {
			throw new SMT.InternalException("jSMTLIB: Error writing to Z3 solver.", e);
		}
	}

	@Override
	public IResponse set_logic(String logicName, /*@Nullable*/ IPos pos) {
		// FIXME - discrimninate among logics
		
		if (smtConfig.verbose != 0) smtConfig.log.logDiag("#set-logic " + logicName);
		if (logicSet) {
			if (!smtConfig.relax) throw new SMT.InternalException("jSMTLIB: Logic is already set");
			pop(pushesDepth);
		}
		pushesDepth++;
		logicSet = true;
		try {
			return parseResponse(solverProcess.sendAndListen("(set-logic ",logicName,")\n"));
		} catch (IOException e) {
			throw new SMT.InternalException("jSMTLIB: Error writing to Z3 solver: " + pos.toString(), e);
		}
	}

	@Override
	public IResponse set_option(IKeyword key, IAttributeValue value) {
		String option = key.value();
		if (Utils.PRINT_SUCCESS.equals(option)) {
			if (!(Utils.TRUE.equals(value) || Utils.FALSE.equals(value))) {
				throw new SMT.InternalException("jSMTLIB: The value of the " + option + " option must be 'true' or 'false'");
			}
		}
		if (logicSet && (smtConfig.utils.INTERACTIVE_MODE.equals(option)||smtConfig.utils.PRODUCE_ASSERTIONS.equals(option))) {
			throw new SMT.InternalException("jSMTLIB: The value of the " + option + " option must be set before the set-logic command");
		}
		if (Utils.PRODUCE_ASSIGNMENTS.equals(option) || 
				Utils.PRODUCE_PROOFS.equals(option)) {
			if (logicSet) throw new SMT.InternalException("jSMTLIB: The value of the " + option + " option must be set before the set-logic command");
			return smtConfig.responseFactory.unsupported();
		}
		if (Utils.PRODUCE_MODELS.equals(option) || Utils.PRODUCE_UNSAT_CORES.equals(option)) {
			if (logicSet) throw new SMT.InternalException("jSMTLIB: The value of the " + option + " option must be set before the set-logic command");
		}
		if (Utils.VERBOSITY.equals(option)) {
			IAttributeValue v = options.get(option);
			smtConfig.verbose = (v instanceof INumeral) ? ((INumeral)v).intValue() : 0;
		} else if (Utils.DIAGNOSTIC_OUTPUT_CHANNEL.equals(option)) {
			// Actually, v should never be anything but IStringLiteral - that should
			// be checked during parsing
			String name = (value instanceof IStringLiteral)? ((IStringLiteral)value).value() : "stderr";
			if (name.equals("stdout")) {
				smtConfig.log.diag = System.out;
			} else if (name.equals("stderr")) {
				smtConfig.log.diag = System.err;
			} else {
				try {
					FileOutputStream f = new FileOutputStream(name,true); // true -> append
					smtConfig.log.diag = new PrintStream(f);
				} catch (java.io.IOException e) {
					throw new SMT.InternalException("jSMTLIB: Failed to open or write to the diagnostic output " + value.pos().toString(), e);
				}
			}
		} else if (Utils.REGULAR_OUTPUT_CHANNEL.equals(option)) {
			// Actually, v should never be anything but IStringLiteral - that should
			// be checked during parsing
			String name = (value instanceof IStringLiteral)?((IStringLiteral)value).value() : "stdout";
			if (name.equals("stdout")) {
				smtConfig.log.out = System.out;
			} else if (name.equals("stderr")) {
				smtConfig.log.out = System.err;
			} else {
				try {
					FileOutputStream f = new FileOutputStream(name,true); // append
					smtConfig.log.out = new PrintStream(f);
				} catch (java.io.IOException e) {
					throw new SMT.InternalException("jSMTLIB: Failed to open or write to the regular output " + value.pos().toString(), e);
				}
			}
		}
		// Save the options on our side as well
		options.put(Utils.INTERACTIVE_MODE.equals(option) && !smtConfig.isVersion(SMTLIB.V20) ? Utils.PRODUCE_ASSERTIONS : option,value);
		IResponse r = checkPrintSuccess(smtConfig,key,value);
		if (r != null) return r;

		try {
			solverProcess.sendAndListen("(set-option ",option," ",value.toString(),")\n");// FIXME - detect errors
		} catch (IOException e) {
			throw new SMT.InternalException("jSMTLIB: Error writing to Z3 solver.", e);
		}
		
		return successOrEmpty(smtConfig);
	}

	@Override
	public IResponse get_option(IKeyword key) { // FIXME - use the solver?
		String option = key.value();
		IAttributeValue value = options.get(Utils.INTERACTIVE_MODE.equals(option) && !smtConfig.isVersion(SMTLIB.V20)? Utils.PRODUCE_ASSERTIONS : option);
		if (value == null) return smtConfig.responseFactory.unsupported();
		return value;
	}

	@Override
	public IResponse get_info(IKeyword key) {
		return sendCommand("(get-info " + key + ")");
	}
	
	@Override
	public IResponse set_info(IKeyword key, IAttributeValue value) {
		if (Utils.infoKeywords.contains(key)) {
			throw new SMT.InternalException("jSMTLIB: Setting the value of a pre-defined keyword is not permitted: "+ 
					smtConfig.defaultPrinter.toString(key) + key.pos().toString());
		}
		return sendCommand(new org.smtlib.command.C_set_info(key,value));
	}


	@Override
	public IResponse declare_fun(Ideclare_fun cmd) {
		if (!logicSet) {
			throw new SMT.InternalException("jSMTLIB: The logic must be set before a declare-fun command is issued");
		}
		try {
			checkSatStatus = null;
			return parseResponse(solverProcess.sendAndListen(translate(cmd),"\n"));
			
		} catch (IOException e) {
			throw new SMT.InternalException("jSMTLIB: Error writing to Z3 solver.", e);
		} catch (IVisitor.VisitorException e) {
			throw new SMT.InternalException("jSMTLIB: Error writing to Z3 solver.", e);
		}
	}

	@Override
	public IResponse echo(IStringLiteral arg) {
		try {
			checkSatStatus = null;
			return parseResponse(solverProcess.sendAndListen(arg.value(),"\n"));
			
		} catch (IOException e) {
			throw new SMT.InternalException("jSMTLIB: Error writing to Z3 solver.", e);
		}
	}

	@Override
	public IResponse define_fun(Idefine_fun cmd) {
		if (!logicSet) {
			throw new SMT.InternalException("jSMTLIB: The logic must be set before a define-fun command is issued");
		}
		try {
			checkSatStatus = null;
			return parseResponse(solverProcess.sendAndListen(translate(cmd),"\n"));
		} catch (IOException e) {
			throw new SMT.InternalException("jSMTLIB: Error writing to Z3 solver.", e);
		} catch (IVisitor.VisitorException e) {
			throw new SMT.InternalException("jSMTLIB: Error writing to Z3 solver.", e);
		}
	}

	@Override
	public IResponse declare_sort(Ideclare_sort cmd) {
		if (!logicSet) {
			throw new SMT.InternalException("jSMTLIB: The logic must be set before a declare-sort command is issued");
		}
		try {
			checkSatStatus = null;
			return parseResponse(solverProcess.sendAndListen(translate(cmd),"\n"));
		} catch (IOException e) {
			throw new SMT.InternalException("jSMTLIB: Error writing to Z3 solver.", e);
		} catch (IVisitor.VisitorException e) {
			throw new SMT.InternalException("jSMTLIB: Error writing to Z3 solver.", e);
		}
	}

	@Override
	public IResponse declare_datatypes(Ideclare_datatypes cmd) {
		if (!logicSet) {
			throw new SMT.InternalException("jSMTLIB: The logic must be set before a declare-datatypes command is issued");
		}
		try {
			checkSatStatus = null;
			return parseResponse(solverProcess.sendAndListen(translate(cmd),"\n"));
		} catch (IOException e) {
			throw new SMT.InternalException("jSMTLIB: Error writing to Z3 solver.", e);
		} catch (IVisitor.VisitorException e) {
			throw new SMT.InternalException("jSMTLIB: Error writing to Z3 solver.", e);
		}
	}

	@Override
	public IResponse declare_const(Ideclare_const cmd) {
		if (!logicSet) {
			throw new SMT.InternalException("The logic must be set before a declare-const command is issued");
		}
		try {
			checkSatStatus = null;
			return parseResponse(solverProcess.sendAndListen(translate(cmd),"\n"));
		} catch (IOException e) {
			throw new SMT.InternalException("Error writing to Z3 solver.", e);
		} catch (IVisitor.VisitorException e) {
			throw new SMT.InternalException("Error writing to Z3 solver.", e);
		}
	}

	@Override
	public IResponse define_sort(Idefine_sort cmd) {
		if (!logicSet) {
			throw new SMT.InternalException("jSMTLIB: The logic must be set before a define-sort command is issued");
		}
		try {
			checkSatStatus = null;
			return parseResponse(solverProcess.sendAndListen(translate(cmd),"\n"));
		} catch (IOException e) {
			throw new SMT.InternalException("jSMTLIB: Error writing to Z3 solver.", e);
		} catch (IVisitor.VisitorException e) {
			throw new SMT.InternalException("jSMTLIB: Error writing to Z3 solver.", e);
		}
	}
	
	@Override 
	public IResponse get_proof() {
		if (!Utils.TRUE.equals(get_option(smtConfig.exprFactory.keyword(Utils.PRODUCE_PROOFS)))) {
			throw new SMT.InternalException("jSMTLIB: The get-proof command is only valid if :produce-proofs has been enabled");
		}
		if (checkSatStatus != smtConfig.responseFactory.unsat()) {
			throw new SMT.InternalException("jSMTLIB: The get-proof command is only valid immediately after check-sat returned unsat");
		}
		try {
			return parseResponse(solverProcess.sendAndListen("(get-proof)\n"));
		} catch (IOException e) {
			throw new SMT.InternalException("jSMTLIB: Error writing to Z3 solver.", e);
		}
	}

	@Override 
	public IResponse get_unsat_core() {
		if (!Utils.TRUE.equals(get_option(smtConfig.exprFactory.keyword(Utils.PRODUCE_UNSAT_CORES)))) {
			throw new SMT.InternalException("jSMTLIB: The get-unsat-core command is only valid if :produce-unsat-cores has been enabled");
		}
		if (checkSatStatus != smtConfig.responseFactory.unsat()) {
			throw new SMT.InternalException("jSMTLIB: The get-unsat-core command is only valid immediately after check-sat returned unsat");
		}
		try {
			IResponse response = parseResponse(solverProcess.sendAndListen("(get-unsat-core)\n"));
			if (!(response instanceof Sexpr.Seq)) {
				throw new SMT.InternalException("jSMTLIB: get_unsat_core did not return a list of symbols, but\n" + response.toString());
			}
			return response;
		} catch (IOException e) {
			throw new SMT.InternalException("jSMTLIB: Error writing to Z3 solver.", e);
		}
	}

	@Override 
	public IResponse get_model() {
		if (!Utils.TRUE.equals(get_option(smtConfig.exprFactory.keyword(Utils.PRODUCE_MODELS)))) {
			throw new SMT.InternalException("jSMTLIB: The get-model command is only valid if :produce-models has been enabled");
		}
		if (checkSatStatus != smtConfig.responseFactory.sat()) {
			throw new SMT.InternalException("jSMTLIB: The get-model command is only valid immediately after check-sat returned sat");
		}
		try {
			return parseResponse(solverProcess.sendAndListen("(get-model)\n"));
		} catch (IOException e) {
			throw new SMT.InternalException("jSMTLIB: Error writing to Z3 solver.", e);
		}
	}

	@Override 
	public IResponse get_assignment() {
		// FIXME - do we really want to call get-option here? it involves going to the solver?
		if (!Utils.TRUE.equals(get_option(smtConfig.exprFactory.keyword(Utils.PRODUCE_ASSIGNMENTS)))) {
			throw new SMT.InternalException("jSMTLIB: The get-assignment command is only valid if :produce-assignments has been enabled");
		}
		if (checkSatStatus != smtConfig.responseFactory.sat() && checkSatStatus != smtConfig.responseFactory.unknown()) {
			throw new SMT.InternalException("jSMTLIB: The get-assignment command is only valid immediately after check-sat returned sat or unknown");
		}
		try {
			return parseResponse(solverProcess.sendAndListen("(get-assignment)\n"));
		} catch (IOException e) {
			throw new SMT.InternalException("jSMTLIB: Error writing to Z3 solver.", e);
		}
	}

	public IExpr translateSExprToIExpr(ISexpr sexpr) {
		switch (sexpr.kind()) {
			case "sequence":
			    List<ISexpr> tail = ((Sexpr.Seq)sexpr).sexprs();
			    ISexpr head = tail.remove(0);
				if (!("token".equals(head.kind()) || "symbol".equals(head.kind()))) {
					throw new SMT.InternalException("Cannot translate SExpr.Seq to IExpr since it does not start with a token:\n" + sexpr.toString() + "\n" + head.kind());
				}
				// handle the special case of let-expressions
				if ("let".equals(head.toString())) {
					if (tail.size() != 2) {
						throw new SMT.InternalException("Let-expression has an invalid number of arguments. Expected: 2, Actual: " + Integer.toString(tail.size()));
					}
					ISexpr bindings = tail.remove(0);
					ISexpr expr = tail.remove(0);
					if (!"sequence".equals(bindings.kind())) {
						throw new SMT.InternalException("first argument of a let-expression must be a sequence of bindings.");
					}
					List<IExpr.IBinding> translatedBindings = new LinkedList<IExpr.IBinding>();
					for (ISexpr binding : ((Sexpr.Seq)bindings).sexprs()) {
						if (!"sequence".equals(binding.kind())) {
							throw new SMT.InternalException("bindings of let-expressions should be sequences.");
						}
						List<ISexpr> subs = ((Sexpr.Seq)binding).sexprs();
						if (subs.size() != 2) {
							throw new SMT.InternalException("bindings of let-expressions are supposed to contain exactly 2 elements.");
						}
						ISexpr varname = subs.remove(0);
						IExpr varExpr = translateSExprToIExpr(subs.remove(0));
						translatedBindings.add(smtConfig.exprFactory.binding(smtConfig.exprFactory.symbol(varname.toString()), varExpr));
					}
					return smtConfig.exprFactory.let(translatedBindings, translateSExprToIExpr(expr));
				}
				// translate to function application

				List<IExpr> translatedTail = new LinkedList<IExpr>();
				for (ISexpr sub : tail) {
					translatedTail.add(translateSExprToIExpr(sub));
				}
				return smtConfig.exprFactory.fcn(smtConfig.exprFactory.symbol(head.toString()), translatedTail);
			case "token":
				return smtConfig.exprFactory.symbol(((Sexpr.Token)sexpr).toString());
			case "symbol":
				return smtConfig.exprFactory.symbol(((SMTExpr.Symbol)sexpr).value());
			case "decimal":
				return smtConfig.exprFactory.decimal(sexpr.toString());
			case "numeral":
				return smtConfig.exprFactory.numeral(sexpr.toString());
			case "Expr":
				return ((Sexpr.Expr)sexpr).expr;
			case "string-literal":
				return smtConfig.exprFactory.quotedString(sexpr.toString());
			default:
				throw new SMT.InternalException("encountered unknown kind of SExpr: '" + sexpr.kind() + "':" + sexpr.toString());
		}
	}

	@Override 
	public IResponse get_value(IExpr... terms) {
		// FIXME - do we really want to call get-option here? it involves going to the solver?
		if (!Utils.TRUE.equals(get_option(smtConfig.exprFactory.keyword(Utils.PRODUCE_MODELS)))) {
			throw new SMT.InternalException("jSMTLIB: The get-value command is only valid if :produce-models has been enabled");
		}
		if (!smtConfig.responseFactory.sat().equals(checkSatStatus) && !smtConfig.responseFactory.unknown().equals(checkSatStatus)) {
			throw new SMT.InternalException("jSMTLIB: A get-value command is valid only after check-sat has returned sat or unknown");
		}
		try {
			solverProcess.sendNoListen("(get-value (");
			for (IExpr e: terms) {
				solverProcess.sendNoListen(" ",translate(e));
			}
			String r = solverProcess.sendAndListen("))\n");
			IResponse response = parseResponse(r);
//			return response;

// old version:
//			if (response instanceof ISeq) {
//				List<ISexpr> valueslist = new LinkedList<ISexpr>();
//				Iterator<ISexpr> iter = ((ISeq)response).sexprs().iterator();
//				for (IExpr e: terms) {
//					if (!iter.hasNext()) break;
//					List<ISexpr> values = new LinkedList<ISexpr>();
//					values.add(new Sexpr.Expr(e));
//					values.add(iter.next());
//					valueslist.add(new Sexpr.Seq(values));
//				}	
//				return new Sexpr.Seq(valueslist);
//			}

			// rewrite the SExpr to a proper IValueResponse
			if (!(response instanceof Sexpr.Seq)) {
				throw new SMT.InternalException("get_value did not return a list of values, but\n" + response.toString());
			} 
			List<IResponse.IPair<IExpr, IExpr>> result = new LinkedList<IResponse.IPair<IExpr, IExpr>>(); 
			for (ISexpr sub: ((Sexpr.Seq) response).sexprs()) {
				if (!(sub instanceof Sexpr.Seq)) { throw new SMT.InternalException("jSMTLIB: an entry in the list of values returned by get_value is not a pair, but\n" + sub.toString()); } 
				IExpr.ISymbol var = (IExpr.ISymbol)(((Sexpr.Seq) sub).sexprs().get(0));
				IExpr value = translateSExprToIExpr(((Sexpr.Seq) sub).sexprs().get(1));
				result.add(smtConfig.responseFactory.pair(var, value)); 
			}
			return smtConfig.responseFactory.get_value_response(result);
		} catch (IOException e) {
			throw new SMT.InternalException("jSMTLIB: Error writing to Z3 solver.", e);
		} catch (IVisitor.VisitorException e) {
			throw new SMT.InternalException("jSMTLIB: Error writing to Z3 solver.", e);
		}
	}

	public class Translator extends Printer { //extends IVisitor.NullVisitor<String> {
		
		public Translator(Writer w) { super(w); }

//		@Override
//		public String visit(IDecimal e) throws IVisitor.VisitorException {
//			return translateSMT(e);
//		}
//
//		@Override
//		public String visit(IStringLiteral e) throws IVisitor.VisitorException {
//			throw new VisitorException("The Z3 solver cannot handle string literals",e.pos());
//		}
//
//		@Override
//		public String visit(INumeral e) throws IVisitor.VisitorException {
//			return e.value().toString();
//		}
//
//		@Override
//		public String visit(IBinaryLiteral e) throws IVisitor.VisitorException {
//			return "#b" + e.value();
//		}
//
//		@Override
//		public String visit(IHexLiteral e) throws IVisitor.VisitorException {
//			return "#x" + e.value();
//		}

		@Override
		public Void visit(IFcnExpr e) throws IVisitor.VisitorException {
			// Only - for >=2 args is not correctly done, but we can't delegate to translateSMT because it might be a sub-expression.
			Iterator<IExpr> iter = e.args().iterator();
			// TODO: BEN: what is this restriction good for? Why should a function call not be allowed to have no arguments?
			//  if (!iter.hasNext()) throw new SMTLIBRuntimeException("Did not expect an empty argument list in function call (" + e.head().toString() + ")");
			IQualifiedIdentifier fcn = e.head();
			int length = e.args().size();
			if (length > 2 && (fcn instanceof IIdentifier) && fcn.toString().equals("-")) {
				leftassoc(fcn.toString(),length,iter);
			} else {
				super.visit(e);
			}
			return null;
//			String fcnname = fcn.accept(this);
//			StringBuilder sb = new StringBuilder();
//			int length = e.args().size();
//			if (length > 2 && (fcnname.equals("=") || fcnname.equals("<") || fcnname.equals(">") || fcnname.equals("<=") || fcnname.equals(">="))) {
//				// chainable
//				return chainable(fcnname,iter);
//			} else if (fcnname.equals("xor")) {
//				// left-associative operators that need grouping
//				return leftassoc(fcnname,length,iter);
//			} else if (length > 1 && fcnname.equals("-")) {
//				// left-associative operators that need grouping
//				return leftassoc(fcnname,length,iter);
//			} else if (fcnname.equals("=>")) {
//				// right-associative operators that need grouping
//				if (!iter.hasNext()) {
//					throw new VisitorException("=> operation without arguments",e.pos());
//				}
//				return rightassoc(fcnname,iter);
//			} else {
//				// no associativity 
//				sb.append("(");
//				sb.append(fcnname);
//				while (iter.hasNext()) {
//					sb.append(" ");
//					sb.append(iter.next().accept(this));
//				}
//				sb.append(")");
//				return sb.toString();
//			}
		}

		//@ requires iter.hasNext();
		//@ requires length > 0;
		protected <T extends IExpr> void leftassoc(String fcnname, int length, Iterator<T> iter ) throws IVisitor.VisitorException {
			if (length == 1) {
				iter.next().accept(this);
			} else {
				try {
					w.append("(");
					w.append(fcnname);
					w.append(" ");
					leftassoc(fcnname,length-1,iter);
					w.append(" ");
					iter.next().accept(this);
					w.append(")");
				} catch (IOException ex) {
					throw new IVisitor.VisitorException(ex,null); // FIXME - null ?
				}
			}
		}

		//@ requires iter.hasNext();
		protected <T extends IExpr> void rightassoc(String fcnname, Iterator<T> iter ) throws IVisitor.VisitorException {
			T n = iter.next();
			if (!iter.hasNext()) {
				n.accept(this);
			} else {
				try {
					w.append("(");
					w.append(fcnname);
					w.append(" ");
					n.accept(this);
					w.append(" ");
					rightassoc(fcnname,iter);
					w.append(")");
				} catch (IOException ex) {
					throw new IVisitor.VisitorException(ex,null); // FIXME - null ?
				}
			}
		}

		
		//@ requires iter.hasNext();
		//@ requires length > 0;
		protected <T extends IAccept> void chainable(String fcnname, Iterator<T> iter ) throws IVisitor.VisitorException {
			try {
				w.append("(and ");
				T left = iter.next();
				while (iter.hasNext()) {
					w.append("(");
					w.append(fcnname);
					w.append(" ");
					left.accept(this);
					w.append(" ");
					(left=iter.next()).accept(this);
					w.append(")");
				}
				w.append(")");
			} catch (IOException ex) {
				throw new IVisitor.VisitorException(ex,null); // FIXME - null ?
			}
		}


//		@Override
//		public String visit(ISymbol e) throws IVisitor.VisitorException {
//			return translateSMT(e);
//		}
//
//		@Override
//		public String visit(IKeyword e) throws IVisitor.VisitorException {
//			throw new VisitorException("Did not expect a Keyword in an expression to be translated",e.pos());
//		}
//
//		@Override
//		public String visit(IError e) throws IVisitor.VisitorException {
//			throw new VisitorException("Did not expect a Error token in an expression to be translated", e.pos());
//		}
//
//		private final String zeros = "00000000000000000000000000000000000000000000000000";
//		@Override
//		public String visit(IParameterizedIdentifier e) throws IVisitor.VisitorException {
//			return translateSMT(e);
//		}
//
//		@Override
//		public String visit(IAsIdentifier e) throws IVisitor.VisitorException {
//			return translateSMT(e);
//		}
//
//		@Override
//		public String visit(IForall e) throws IVisitor.VisitorException {
//			return translateSMT(e);
//		}
//
//		@Override
//		public String visit(IExists e) throws IVisitor.VisitorException {
//			return translateSMT(e);
//		}
//
//		@Override
//		public String visit(ILet e) throws IVisitor.VisitorException {
//			return translateSMT(e);
//		}
//
//		@Override
//		public String visit(IAttribute<?> e) throws IVisitor.VisitorException {
//			throw new UnsupportedOperationException("visit-IAttribute");
//		}
//
//		@Override
//		public String visit(IAttributedExpr e) throws IVisitor.VisitorException {
//			return translateSMT(e);
//		}
//
//		@Override
//		public String visit(IDeclaration e) throws IVisitor.VisitorException {
//			throw new UnsupportedOperationException("visit-IDeclaration");
//		}
//
//		@Override
//		public String visit(ISort.IFamily s) throws IVisitor.VisitorException {
//			return s.identifier().accept(this);
//		}
//		
//		@Override
//		public String visit(ISort.IAbbreviation s) throws IVisitor.VisitorException {
//			throw new UnsupportedOperationException("visit-ISort.IAbbreviation");
//		}
//		
//		@Override
//		public String visit(ISort.IApplication s) throws IVisitor.VisitorException {
//			return translateSMT(s);
//		}
//		
//		@Override
//		public String visit(ISort.IFcnSort s) throws IVisitor.VisitorException {
//			throw new UnsupportedOperationException("visit-ISort.IFcnSort");
//		}
//		
//		@Override
//		public String visit(ISort.IParameter s) throws IVisitor.VisitorException {
//			throw new UnsupportedOperationException("visit-ISort.IParameter");
//		}
//		
//		@Override
//		public String visit(ICommand command) throws IVisitor.VisitorException {
//			if (command instanceof ICommand.Iassert) {
//				return "(assert " + ((ICommand.Iassert)command).expr().accept(this) + ")";
//			} else {
//				return translateSMT(command);
//			}
//		}
	}
}
