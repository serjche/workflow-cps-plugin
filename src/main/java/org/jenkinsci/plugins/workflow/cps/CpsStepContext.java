/*
 * The MIT License
 *
 * Copyright (c) 2013-2014, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.workflow.cps;

import com.cloudbees.groovy.cps.Outcome;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import groovy.lang.Closure;
import hudson.model.Descriptor;
import hudson.model.Result;
import hudson.util.DaemonThreadFactory;
import hudson.util.NamingThreadFactory;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.cps.nodes.StepEndNode;
import org.jenkinsci.plugins.workflow.cps.nodes.StepStartNode;
import org.jenkinsci.plugins.workflow.cps.persistence.PersistIn;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.graph.AtomNode;
import org.jenkinsci.plugins.workflow.graph.BlockEndNode;
import org.jenkinsci.plugins.workflow.graph.BlockStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.support.DefaultStepContext;
import org.jenkinsci.plugins.workflow.support.concurrent.Futures;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.GuardedBy;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.CauseOfInterruption;
import jenkins.util.ContextResettingExecutorService;
import org.codehaus.groovy.runtime.InvokerInvocationException;
import org.jenkinsci.plugins.workflow.cps.nodes.StepNode;

import static org.jenkinsci.plugins.workflow.cps.persistence.PersistenceContext.*;

/**
 * {@link StepContext} implementation for CPS.
 *
 * <p>
 * This context behaves in two modes. It starts in the synchronous mode, where if a result is set (or exception
 * is thrown), it just gets recoded. When passed into {@link Step#start(StepContext)}, it's in this mode.
 *
 * <p>
 * When {@link Step#start(StepContext)} method returns, we'll atomically check if the result is set or not
 * and then switch to the asynchronous mode. In this mode, if the result is set, it'll trigger the rehydration
 * of the workflow. If a {@link CpsStepContext} gets serialized, it'll be deserialized in the asynchronous mode.
 *
 * <p>
 * This object must be serializable on its own without sucking in any of the {@link CpsFlowExecution} object
 * graph. Wherever we need {@link CpsFlowExecution} we do that by following {@link FlowExecutionOwner}, and
 * when we need pointers to individual objects inside, we use IDs (such as {@link #id}}.
 *
 * @author Kohsuke Kawaguchi
 * @see Step#start(StepContext)
 */
@PersistIn(ANYWHERE)
@SuppressFBWarnings("SE_TRANSIENT_FIELD_NOT_RESTORED") // bodyInvokers, syncMode handled specially
public class CpsStepContext extends DefaultStepContext { // TODO add XStream class mapper

    private static final Logger LOGGER = Logger.getLogger(CpsStepContext.class.getName());

    @GuardedBy("this")
    private transient Outcome outcome;
    private transient Throwable whenOutcomeDelivered;

    // see class javadoc.
    // transient because if it's serialized and deserialized, it should come back in the async mode.
    private transient boolean syncMode = true;

    /**
     * This object gets serialized independently from the rest of {@link CpsFlowExecution}
     * and {@link DSL}, so it needs to use a handle to refer back to {@link CpsFlowExecution}
     */
    private final FlowExecutionOwner executionRef;

    /**
     * {@link FlowNode#id} that points to the atom node created for this step.
     */
    private final String id;

    /**
     * Keeps an in-memory reference to {@link FlowNode} to speed up the synchronous execution.
     *
     * If there's a body, this field is {@link BlockStartNode}. If there's no body, then this
     * field is {@link AtomNode}
     *
     * @see #getNode()
     */
    /*package*/ transient FlowNode node;

    /*

        TODO: parallel step implementation

        when forking off another branch of parallel, call the 3-arg version of the start() method,
        and have its callback insert the ID of the new head at the end of the thread
     */
    /**
     * {@link FlowHead#getId()} that should become
     * the parents of the {@link BlockEndNode} when we create one. Only used when this context has the body.
     */
    final List<Integer> bodyHeads = new ArrayList<>();

    /**
     * If the invocation of the body is requested, this object remembers how to start it.
     *
     * <p>
     * Only used in the synchronous mode while {@link CpsFlowExecution} is in the RUNNABLE state,
     * so this need not be persisted. To preserve the order of invocation in the flow graph,
     * this needs to be a list and not set.
     */
    transient List<CpsBodyInvoker> bodyInvokers = Collections.synchronizedList(new ArrayList<>());

    /**
     * While {@link CpsStepContext} has not received teh response, maintains the body closure.
     *
     * This is the implicit closure block passed to the step invocation.
     */
    private @CheckForNull BodyReference body;

    private final int threadId;

    /**
     * {@linkplain Descriptor#getId() step descriptor ID}.
     */
    private final String stepDescriptorId;

    /**
     * Resolved result of {@link #stepDescriptorId} to make the look up faster.
     */
    private transient volatile StepDescriptor stepDescriptor;

    /**
     * Cached value of {@link #getThreadGroupSynchronously}.
     * Never null once set (might be overwritten).
     */
    private transient volatile CpsThreadGroup threadGroup;
    private transient volatile boolean loadingThreadGroup;

    @CpsVmThreadOnly
    CpsStepContext(StepDescriptor step, CpsThread thread, FlowExecutionOwner executionRef, FlowNode node, @CheckForNull Closure body) {
        this.threadId = thread.id;
        this.executionRef = executionRef;
        this.id = node.getId();
        this.node = node;
        this.body = body != null ? thread.group.export(body) : null;
        this.stepDescriptorId = step.getId();
    }

    /**
     * Obtains {@link StepDescriptor} that represents the step this context is invoking.
     *
     * @return
     *      This method returns null if the step descriptor used is not recoverable in the current VM session,
     *      such as when the plugin that implements this was removed. So the caller should defend against null.
     */
    @SuppressFBWarnings(value="RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE", justification="TODO 1.653+ switch to Jenkins.getInstanceOrNull")
    public @CheckForNull StepDescriptor getStepDescriptor() {
        Jenkins j = Jenkins.getInstanceOrNull();
        if (j == null) {
            return null;
        }
        if (stepDescriptor==null)
            stepDescriptor = (StepDescriptor) j.getDescriptor(stepDescriptorId);
        return stepDescriptor;
    }

    public String getDisplayName() {
        StepDescriptor d = getStepDescriptor();
        return d!=null ? d.getDisplayName() : stepDescriptorId;
    }

    @Override protected CpsFlowExecution getExecution() throws IOException {
        return (CpsFlowExecution)executionRef.get();
    }

    /**
     * Returns the thread that is executing this step.
     *
     * @return
     *      null if the thread has finished executing.
     */
    @CheckForNull CpsThread getThread(CpsThreadGroup g) {
        return g.getThread(threadId);
    }

    /**
     * Synchronously resolve the current thread.
     *
     * This can block for the entire duration of the PREPARING state.
     */
    private @CheckForNull CpsThread getThreadSynchronously() throws InterruptedException, IOException {
        return getThread(getThreadGroupSynchronously());
    }

    private @Nonnull CpsThreadGroup getThreadGroupSynchronously() throws InterruptedException, IOException {
        if (threadGroup == null) {
            ListenableFuture<CpsThreadGroup> pp;
            CpsFlowExecution flowExecution = getExecution();
            while ((pp = flowExecution.programPromise) == null) {
                Thread.sleep(100); // TODO does JENKINS-33005 remove the need for this?
            }
            try {
                threadGroup = pp.get();
            } catch (ExecutionException e) {
                throw new IOException(e);
            }
        }
        return threadGroup;
    }

    // As in c16a522, using jenkins.util.Timer for this could deadlock. TODO would like a standard unbounded executor service.
    private static final ExecutorService isReadyExecutorService = new ContextResettingExecutorService(Executors.newCachedThreadPool(new NamingThreadFactory(new DaemonThreadFactory(), "CpsStepContext.isReady")));
    @SuppressFBWarnings("RV_RETURN_VALUE_IGNORED_BAD_PRACTICE")
    @Override public boolean isReady() {
        if (threadGroup == null) {
            if (!loadingThreadGroup) { // but start computing it
                isReadyExecutorService.submit(new Callable<Void>() {
                    @Override public Void call() throws Exception {
                        getThreadGroupSynchronously();
                        return null;
                    }
                });
                loadingThreadGroup = true;
            }
            return false;
        } else {
            return true;
        }
    }

    @Override public boolean hasBody() {
        return body != null;
    }

    @Override
    public CpsBodyInvoker newBodyInvoker() {
        if (body == null) {
            throw new IllegalStateException("There is no body to invoke");
        }
        return newBodyInvoker(body, false);
    }

    public @Nonnull CpsBodyInvoker newBodyInvoker(@Nonnull BodyReference body, boolean unexport) {
        return new CpsBodyInvoker(this, body, unexport);
    }

    @Override
    protected <T> T doGet(Class<T> key) throws IOException, InterruptedException {
        CpsThread t = getThreadSynchronously();
        if (t == null) {
            throw new IOException("cannot find current thread");
        }
        return t.getContextVariable(key, this::getExecution, this::getNode);
    }

    @Override protected FlowNode getNode() throws IOException {
        if (node == null) {
            node = getExecution().getNode(id);
            if (node == null) {
                throw new IOException("no node found for " + id);
            }
        }
        return node;
    }

    @Override public synchronized void onFailure(Throwable t) {
        if (t == null) {
            throw new IllegalArgumentException();
        }
        completed(new Outcome(null, t));
    }

    @Override public synchronized void onSuccess(Object returnValue) {
        completed(new Outcome(returnValue, null));

    }

    private void completed(@Nonnull Outcome newOutcome) {
        if (outcome == null) {
            LOGGER.finer(() -> this + " completed with " + newOutcome);
            outcome = newOutcome;
            scheduleNextRun();
            whenOutcomeDelivered = new Throwable();
        } else {
            Throwable failure = newOutcome.getAbnormal();
            if (failure instanceof FlowInterruptedException) {
                for (CauseOfInterruption cause : ((FlowInterruptedException) failure).getCauses()) {
                    if (cause instanceof BodyFailed) {
                        LOGGER.log(Level.FINE, "already completed " + this + " and now received body failure", failure);
                        // Predictable that the error would be thrown up here; quietly ignore it.
                        return;
                    }
                }
            }
            LOGGER.log(Level.WARNING, "already completed " + this, new IllegalStateException("delivered here"));
            if (failure != null) {
                LOGGER.log(Level.INFO, "new failure", failure);
            } else {
                LOGGER.log(Level.INFO, "new success: {0}", outcome.getNormal());
            }
            if (whenOutcomeDelivered != null) {
                LOGGER.log(Level.INFO, "previously delivered here", whenOutcomeDelivered);
            }
            failure = outcome.getAbnormal();
            if (failure != null) {
                LOGGER.log(Level.INFO, "earlier failure", failure);
            } else {
                LOGGER.log(Level.INFO, "earlier success: {0}", outcome.getNormal());
            }
        }
    }

    /**
     * When this step context has completed execution (successful or otherwise), plan the next action.
     */
    private void scheduleNextRun() {
        if (syncMode) {
            // if we get the result set before the start method returned, then DSL.invokeMethod() will
            // plan the next action.
            return;
        }

        try {
            final FlowNode n = getNode();
            final CpsFlowExecution flow = getExecution();

            final List<FlowNode> parents = new ArrayList<>();
            for (int head : bodyHeads) {
                FlowHead flowHead = flow.getFlowHead(head);
                if (flowHead != null) {
                    parents.add(flowHead.get());
                } else {
                    LOGGER.log(Level.WARNING, "Could not find flow head #{0}", head);
                }
            }

            flow.runInCpsVmThread(new FutureCallback<CpsThreadGroup>() {
                @CpsVmThreadOnly
                @Override
                public void onSuccess(CpsThreadGroup g) {
                    g.unexport(body);
                    body = null;
                    CpsThread thread = getThread(g);
                    if (thread != null) {
                        CpsThread nit = thread.getNextInner();
                        if (nit!=null) {
                            // can't mark this done until the inner thread is done.
                            // defer the processing until the inner thread is done
                            nit.addCompletionHandler(new ScheduleNextRun());
                            if (getOutcome().isFailure()) {
                                // if the step with a currently running body reported a failure,
                                // make some effort to try to interrupt the running body
                                StepExecution s = nit.getStep();
                                if (s != null) {
                                    // TODO: ideally this needs to work like interrupt, in that
                                    // if s==null the next StepExecution gets interrupted when it happen
                                    FlowInterruptedException cause = new FlowInterruptedException(Result.FAILURE, new BodyFailed());
                                    cause.initCause(getOutcome().getAbnormal());
                                    try {
                                        // TODO JENKINS-26148/JENKINS-34637 this is probably wrong: should interrupt the innermost execution
                                        // (the “next” one could be block-scoped, and we would want to interrupt all parallel heads)
                                        s.stop(cause);
                                    } catch (Exception e) {
                                        LOGGER.log(Level.WARNING, "Failed to stop the body execution in response to the failure of the parent");
                                    }
                                }
                            }
                            return;
                        }

                        if (n instanceof StepStartNode) {
                            // if there's no body to invoke, we want the current thread to be the sole head
                            if (parents.isEmpty())
                                parents.add(thread.head.get());

                            // clear all the subsumed heads that are joining. thread that owns parents.get(0) lives on
                            for (int i=1; i<parents.size(); i++)
                                g.getExecution().subsumeHead(parents.get(i));
                            StepEndNode en = new StepEndNode(flow, (StepStartNode) n, parents);
                            thread.head.setNewHead(en);
                        }
                        thread.head.markIfFail(getOutcome());
                        thread.setStep(null);
                        thread.resume(getOutcome());
                    }
                    outcome = new Outcome(null, new AlreadyCompleted());
                }

                /**
                 * Program state failed to load.
                 */
                @Override
                public void onFailure(Throwable t) {
                }
            });
        } catch (IOException x) {
            LOGGER.log(Level.FINE, null, x);
        }
    }

    /**
     * Marker for steps which have completed.
     * We no longer wish to hold on to their live objects as that could be a memory leak.
     * We could use {@code new Outcome(null, null)}
     * but that could be confused with a legitimate null return value;
     * {@link #outcome} must be nonnull for {@link #isCompleted} to work.
     * If this exception appears in the program, something is wrong.
     */
    private static final class AlreadyCompleted extends AssertionError {
        @Override public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    private static class BodyFailed extends CauseOfInterruption {
        @Override public String getShortDescription() {
            return "Body of block-scoped step failed";
        }
    }

    @Override
    public void setResult(Result r) {
        try {
            getExecution().setResult(r);
        } catch (IOException x) {
            LOGGER.log(Level.FINE, null, x);
        }
    }

    synchronized boolean isCompleted() {
        return outcome!=null;
    }

    synchronized boolean isSyncMode() {
        return syncMode;
    }

    /**
     * Simulates the result of the {@link StepContext call} by either throwing an exception
     * or returning the value.
     */
    synchronized Object replay() {
        try {
            return getOutcome().replay();
        } catch (Throwable failure) {
            // Cf. CpsBodyExecution.FailureAdapter:
            if (failure instanceof RuntimeException)
                throw (RuntimeException) failure;
            if (failure instanceof Error)
                throw (Error) failure;
            // Any GroovyRuntimeException is treated magically by ScriptBytecodeAdapter.unwrap (from PogoMetaClassSite):
            throw new InvokerInvocationException(failure);
        }
    }

    synchronized Outcome getOutcome() {
        return outcome;
    }

    /**
     * Atomically switch this context into the asynchronous mode.
     * Any results set beyond this point will trigger callback.
     *
     * @return
     *      true if the result was not available prior to this call and the context was successfully switched to the
     *      async mode.
     *
     *      false if the result is already available. The caller should use {@link #getOutcome()} to obtain that.
     */
    synchronized boolean switchToAsyncMode() {
        if (!syncMode)  throw new AssertionError();
        syncMode = false;
        return !isCompleted();
    }

    @Override public ListenableFuture<Void> saveState() {
        try {
            final SettableFuture<Void> f = SettableFuture.create();
            CpsFlowExecution exec = getExecution();
            if (!exec.getDurabilityHint().isPersistWithEveryStep()) {
                f.set(null);
                return f;
            }

            exec.runInCpsVmThread(new FutureCallback<CpsThreadGroup>() {
                @Override public void onSuccess(CpsThreadGroup result) {
                    try {
                        // TODO keep track of whether the program was saved anyway after saveState was called but before now, and do not bother resaving it in that case
                        if (result.getExecution().getDurabilityHint().isPersistWithEveryStep()) {
                            result.getExecution().getStorage().flush();
                            result.saveProgram();
                        }
                        f.set(null);
                    } catch (Exception x) {
                        f.setException(x);
                    }
                }
                @Override public void onFailure(Throwable t) {
                    f.setException(t);
                }
            });
            return f;
        } catch (IOException x) {
            return Futures.immediateFailedFuture(x);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        CpsStepContext that = (CpsStepContext) o;

        return executionRef.equals(that.executionRef) && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        int result = executionRef.hashCode();
        result = 31 * result + id.hashCode();
        return result;
    }

    @Override public String toString() {
        String function = null;
        if (node instanceof StepNode) {
            StepDescriptor d = ((StepNode) node).getDescriptor();
            if (d != null) {
                function = d.getFunctionName();
            }
        }
        return "CpsStepContext[" + id + ":" + function + "]:" + executionRef;
    }

    private static final long serialVersionUID = 1L;

    @SuppressFBWarnings("SE_INNER_CLASS")
    private class ScheduleNextRun implements FutureCallback<Object>, Serializable {
        public void onSuccess(Object e)    { scheduleNextRun(); }
        public void onFailure(Throwable e) { scheduleNextRun(); }

        private static final long serialVersionUID = 1L;
    }
}
