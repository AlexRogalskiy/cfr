package org.benf.cfr.reader.bytecode;

import org.benf.cfr.reader.bytecode.analysis.opgraph.Op01WithProcessedDataAndByteJumps;
import org.benf.cfr.reader.bytecode.analysis.opgraph.Op02WithProcessedDataAndRefs;
import org.benf.cfr.reader.bytecode.analysis.opgraph.Op03SimpleStatement;
import org.benf.cfr.reader.bytecode.analysis.opgraph.Op04StructuredStatement;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.BlockIdentifierFactory;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.VariableFactory;
import org.benf.cfr.reader.bytecode.opcode.JVMInstr;
import org.benf.cfr.reader.entities.ConstantPool;
import org.benf.cfr.reader.entities.Method;
import org.benf.cfr.reader.entities.attributes.AttributeCode;
import org.benf.cfr.reader.entities.exceptions.ExceptionAggregator;
import org.benf.cfr.reader.util.ListFactory;
import org.benf.cfr.reader.util.bytestream.ByteData;
import org.benf.cfr.reader.util.bytestream.OffsettingByteData;
import org.benf.cfr.reader.util.output.Dumpable;
import org.benf.cfr.reader.util.output.Dumper;
import org.benf.cfr.reader.util.output.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Created by IntelliJ IDEA.
 * User: lee
 * Date: 28/04/2011
 * Time: 07:20
 * To change this template use File | Settings | File Templates.
 */
public class CodeAnalyser {
    private final static Logger logger = LoggerFactory.create(CodeAnalyser.class);

    private final AttributeCode originalCodeAttribute;
    private final ConstantPool cp;

    private Method method;

    private Dumpable start;


    public CodeAnalyser(AttributeCode attributeCode) {
        this.originalCodeAttribute = attributeCode;
        this.cp = attributeCode.getConstantPool();
    }

    public void setMethod(Method method) {
        this.method = method;
    }

    public void analyse() {

        ByteData rawCode = originalCodeAttribute.getRawData();
        long codeLength = originalCodeAttribute.getCodeLength();
        ArrayList<Op01WithProcessedDataAndByteJumps> instrs = new ArrayList<Op01WithProcessedDataAndByteJumps>();
        Map<Integer, Integer> lutByOffset = new HashMap<Integer, Integer>();
        Map<Integer, Integer> lutByIdx = new HashMap<Integer, Integer>();
        OffsettingByteData bdCode = rawCode.getOffsettingOffsetData(0);
        int idx = 1;
        int offset = 0;

        // We insert a fake NOP right at the start, so that we always know that each operation has a valid
        // parent.  This sentinel assumption is used when inserting try { catch blocks.
        instrs.add(JVMInstr.NOP.createOperation(null, cp, -1));
        lutByIdx.put(0, -1);
        lutByOffset.put(-1, 0);
        do {
            JVMInstr instr = JVMInstr.find(bdCode.getS1At(0));
            Op01WithProcessedDataAndByteJumps oc = instr.createOperation(bdCode, cp, offset);
            int length = oc.getInstructionLength();
            lutByOffset.put(offset, idx);
            lutByIdx.put(idx, offset);
            instrs.add(oc);
            offset += length;
            bdCode.advance(length);
            idx++;
        } while (offset < codeLength);

        List<Op01WithProcessedDataAndByteJumps> op1list = ListFactory.newList();
        List<Op02WithProcessedDataAndRefs> op2list = ListFactory.newList();
        // Now walk the indexed ops
        for (int x = 0; x < instrs.size(); ++x) {
            Op01WithProcessedDataAndByteJumps op1 = instrs.get(x);
            op1list.add(op1);
            Op02WithProcessedDataAndRefs op2 = op1.createOp2(cp, x);
            op2list.add(op2);
        }

        for (int x = 0; x < instrs.size(); ++x) {
            int offsetOfThisInstruction = lutByIdx.get(x);
            int[] targetIdxs = op1list.get(x).getAbsoluteIndexJumps(offsetOfThisInstruction, lutByOffset);
            Op02WithProcessedDataAndRefs source = op2list.get(x);
            for (int targetIdx : targetIdxs) {
                Op02WithProcessedDataAndRefs target = op2list.get(targetIdx);
                source.addTarget(target);
                target.addSource(source);
            }
        }

        BlockIdentifierFactory blockIdentifierFactory = new BlockIdentifierFactory();
        ExceptionAggregator exceptions = new ExceptionAggregator(originalCodeAttribute.getExceptionTableEntries(), blockIdentifierFactory, cp);
        //
        // We know the ranges covered by each exception handler - insert try / catch statements around
        // these ranges.
        //
        op2list = Op02WithProcessedDataAndRefs.insertExceptionBlocks(op2list, exceptions, lutByOffset, cp);
        lutByOffset = null; // No longer valid.


        // Populate stack info (each instruction gets references to stack objects
        // consumed / produced.
        Op02WithProcessedDataAndRefs.populateStackInfo(op2list);

        Dumper dumper = new Dumper();
////
//        dumper.dump(op2list);

        // Create a non final version...
        final VariableFactory variableFactory = new VariableFactory(method);
        List<Op03SimpleStatement> op03SimpleParseNodes = Op02WithProcessedDataAndRefs.convertToOp03List(op2list, variableFactory, blockIdentifierFactory);


        // Expand any 'multiple' statements (eg from dups)
        Op03SimpleStatement.flattenCompoundStatements(op03SimpleParseNodes);

        dumper.print("Raw Op3 statements:\n");
        op03SimpleParseNodes.get(0).dump(dumper);

//        Op03SimpleStatement.findGenericTypes(op03SimpleParseNodes, cp);

        // Expand raw switch statements into more useful ones.
        Op03SimpleStatement.replaceRawSwitches(op03SimpleParseNodes, blockIdentifierFactory);
        op03SimpleParseNodes = Op03SimpleStatement.renumber(op03SimpleParseNodes);

        // Remove 2nd (+) jumps in pointless jump chains.
        Op03SimpleStatement.removePointlessJumps(op03SimpleParseNodes);

        op03SimpleParseNodes = Op03SimpleStatement.renumber(op03SimpleParseNodes);

        Op03SimpleStatement.assignSSAIdentifiers(op03SimpleParseNodes);

        // Condense pointless assignments
        Op03SimpleStatement.condenseLValues(op03SimpleParseNodes);
        op03SimpleParseNodes = Op03SimpleStatement.renumber(op03SimpleParseNodes);


        // Rewrite new / constructor pairs.
        Op03SimpleStatement.condenseConstruction(op03SimpleParseNodes);
        Op03SimpleStatement.condenseLValues(op03SimpleParseNodes);
        op03SimpleParseNodes = Op03SimpleStatement.renumber(op03SimpleParseNodes);
        // Condense again, now we've simplified constructors.
        Op03SimpleStatement.condenseLValues(op03SimpleParseNodes);
        op03SimpleParseNodes = Op03SimpleStatement.renumber(op03SimpleParseNodes);

        logger.info("collapseAssignmentsIntoConditionals");
        Op03SimpleStatement.collapseAssignmentsIntoConditionals(op03SimpleParseNodes);

        // Collapse conditionals into || / &&
        logger.info("condenseConditionals");
        Op03SimpleStatement.condenseConditionals(op03SimpleParseNodes);
        logger.info("simplifyConditionals");
        Op03SimpleStatement.simplifyConditionals(op03SimpleParseNodes);
        op03SimpleParseNodes = Op03SimpleStatement.renumber(op03SimpleParseNodes);

        // Rewrite conditionals which jump into an immediate jump (see specifics)
        logger.info("rewriteNegativeJumps");
        Op03SimpleStatement.rewriteNegativeJumps(op03SimpleParseNodes);

        Op03SimpleStatement.optimiseForTypes(op03SimpleParseNodes);

        // Identify simple while loops.
        logger.info("identifyLoops1");
        Op03SimpleStatement.identifyLoops1(op03SimpleParseNodes, blockIdentifierFactory);

        // Perform this before simple forward if detection, as it allows us to not have to consider
        // gotos which have been relabelled as continue/break.
        logger.info("rewriteBreakStatements");
        Op03SimpleStatement.rewriteBreakStatements(op03SimpleParseNodes);
        logger.info("rewriteWhilesAsFors");
        Op03SimpleStatement.rewriteWhilesAsFors(op03SimpleParseNodes);

        logger.info("identifyCatchBlocks");
        Op03SimpleStatement.identifyCatchBlocks(op03SimpleParseNodes, blockIdentifierFactory);

        logger.info("removeSynchronizedCatchBlocks");
        Op03SimpleStatement.removeSynchronizedCatchBlocks(op03SimpleParseNodes);

        // identify conditionals which are of the form if (a) { xx } [ else { yy } ]
        // where xx and yy have no GOTOs in them.
        logger.info("identifyNonjumpingConditionals");
        Op03SimpleStatement.identifyNonjumpingConditionals(op03SimpleParseNodes, blockIdentifierFactory);

        logger.info("removeUselessNops");
        op03SimpleParseNodes = Op03SimpleStatement.removeUselessNops(op03SimpleParseNodes);

        // By now, we've (re)moved several statements, so it's possible that some jumps can be rewritten to
        // breaks again.
        logger.info("removePointlessJumps");
        Op03SimpleStatement.removePointlessJumps(op03SimpleParseNodes);
        logger.info("rewriteBreakStatements");
        Op03SimpleStatement.rewriteBreakStatements(op03SimpleParseNodes);

        // Introduce java 6 style for (x : array)
        logger.info("rewriteArrayForLoops");
        Op03SimpleStatement.rewriteArrayForLoops(op03SimpleParseNodes);
        // and for (x : iterable)
        logger.info("rewriteIteratorWhileLoops");
        Op03SimpleStatement.rewriteIteratorWhileLoops(op03SimpleParseNodes);

        logger.info("findSynchronizedBlocks");
        Op03SimpleStatement.findSynchronizedBlocks(op03SimpleParseNodes);


        logger.info("removeUselessNops");
        op03SimpleParseNodes = Op03SimpleStatement.removeUselessNops(op03SimpleParseNodes);

        dumper.print("Final Op3 statements:\n");
        op03SimpleParseNodes.get(0).dump(dumper);
        dumper.print("#############\n");
        Op03SimpleStatement.dumpAll(op03SimpleParseNodes, dumper);


        Op04StructuredStatement block = Op03SimpleStatement.createInitialStructuredBlock(op03SimpleParseNodes);

        this.start = block;
    }


    public void dump(Dumper d) {
        d.newln();
        start.dump(d);
    }

}
