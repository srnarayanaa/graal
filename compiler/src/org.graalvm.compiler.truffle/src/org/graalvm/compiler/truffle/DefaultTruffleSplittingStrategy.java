/*
 * Copyright (c) 2013, 2014, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.graalvm.compiler.truffle;

import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeCost;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.nodes.NodeUtil.NodeCountFilter;
import com.oracle.truffle.api.nodes.RootNode;

import java.util.concurrent.atomic.AtomicInteger;

import static org.graalvm.compiler.truffle.TruffleCompilerOptions.TruffleSplitting;
import static org.graalvm.compiler.truffle.TruffleCompilerOptions.TruffleSplittingLimitGrowth;
import static org.graalvm.compiler.truffle.TruffleCompilerOptions.TruffleSplittingMaxCalleeSize;
import static org.graalvm.compiler.truffle.TruffleCompilerOptions.TruffleSplittingMaxNumberOfSplits;

public final class DefaultTruffleSplittingStrategy implements TruffleSplittingStrategy {

    private final OptimizedDirectCallNode call;
    GraalTVMCI tvmci;

    public DefaultTruffleSplittingStrategy(OptimizedDirectCallNode call, GraalTVMCI tvmci) {
        this.call = call;
        this.tvmci = tvmci;
    }

    private static void incrementSplitCout(GraalTVMCI tvmci, OptimizedDirectCallNode call) {
        final GraalTVMCI.EngineData engineData = tvmci.getEngineData(call.getRootNode());
        engineData.splitCount++;
    }

    @Override
    public void beforeCall(Object[] arguments) {
        if (call.getCallCount() == 2) {
            if (shouldSplit()) {
                incrementSplitCout(tvmci, call);
                call.split();
            }
        }
    }

    @Override
    public void forceSplitting() {
        if (!canSplit()) {
            return;
        }
        incrementSplitCout(tvmci, call);
        call.split();
    }

    private boolean canSplit() {
        if (call.isCallTargetCloned()) {
            return false;
        }
        if (!TruffleCompilerOptions.getValue(TruffleSplitting)) {
            return false;
        }
        if (!call.isCallTargetCloningAllowed()) {
            return false;
        }
        return true;
    }

    private boolean shouldSplit() {
        if (!canSplit()) {
            return false;
        }

        OptimizedCallTarget callTarget = call.getCallTarget();
        int nodeCount = callTarget.getNonTrivialNodeCount();
        if (nodeCount > TruffleCompilerOptions.getValue(TruffleSplittingMaxCalleeSize)) {
            return false;
        }

        RootNode rootNode = call.getRootNode();
        if (rootNode == null) {
            return false;
        }
        // disable recursive splitting for now
        OptimizedCallTarget root = (OptimizedCallTarget) rootNode.getCallTarget();
        if (root == callTarget || root.getSourceCallTarget() == callTarget) {
            // recursive call found
            return false;
        }

        // Disable splitting if it will cause a deep split-only recursion
        if (isRecursiveSplit(call)) {
            return false;
        }
        final GraalTVMCI.EngineData engineData = tvmci.getEngineData(call.getRootNode());
        if (engineData.splitCount > engineData.splitLimit) {
            return false;
        }

        // max one child call and callCount > 2 and kind of small number of nodes
        if (isMaxSingleCall(call)) {
            return true;
        }

        return countPolymorphic(call) >= 1;
    }

    private static boolean isRecursiveSplit(OptimizedDirectCallNode call) {
        final OptimizedCallTarget splitCandidateTarget = call.getCallTarget();

        OptimizedCallTarget callRootTarget = (OptimizedCallTarget) call.getRootNode().getCallTarget();
        OptimizedCallTarget callSourceTarget = callRootTarget.getSourceCallTarget();
        int depth = 0;
        while (callSourceTarget != null) {
            if (callSourceTarget == splitCandidateTarget) {
                depth++;
                if (depth == 2) {
                    return true;
                }
            }
            final OptimizedDirectCallNode splitCallSite = callRootTarget.getCallSiteForSplit();
            if (splitCallSite == null) {
                break;
            }
            final RootNode splitCallSiteRootNode = splitCallSite.getRootNode();
            if (splitCallSiteRootNode == null) {
                break;
            }
            callRootTarget = (OptimizedCallTarget) splitCallSiteRootNode.getCallTarget();
            if (callRootTarget == null) {
                break;
            }
            callSourceTarget = callRootTarget.getSourceCallTarget();
        }
        return false;
    }

    private static boolean isMaxSingleCall(OptimizedDirectCallNode call) {
        return NodeUtil.countNodes(call.getCallTarget().getRootNode(), new NodeCountFilter() {
            @Override
            public boolean isCounted(Node node) {
                return node instanceof DirectCallNode;
            }
        }) <= 1;
    }

    private static int countPolymorphic(OptimizedDirectCallNode call) {
        return NodeUtil.countNodes(call.getCallTarget().getRootNode(), new NodeCountFilter() {
            @Override
            public boolean isCounted(Node node) {
                NodeCost cost = node.getCost();
                boolean polymorphic = cost == NodeCost.POLYMORPHIC || cost == NodeCost.MEGAMORPHIC;
                return polymorphic;
            }
        });
    }

}
