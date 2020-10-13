/*
 * Copyright (c) 2017, 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.graalvm.polyglot.PolyglotException;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage.ContextReference;
import com.oracle.truffle.api.TruffleLanguage.Env;
import com.oracle.truffle.api.nodes.Node;

/**
 * A handle on a context of a set of Truffle languages. This context handle is designed to be used
 * by Truffle guest language implementations. The Truffle context can be used to create inner
 * contexts for isolated execution of guest language code.
 * <p>
 * A {@link TruffleContext context} consists of a {@link TruffleLanguage#createContext(Env) language
 * context} instance for each {@link Env#getInternalLanguages() installed language}. The current
 * language context is {@link TruffleLanguage#createContext(Env) created} eagerly and can be
 * accessed using a {@link ContextReference context reference} or statically with
 * {@link TruffleLanguage#getCurrentContext(Class)} after the context was
 * {@link TruffleContext#enter() entered}.
 * <p>
 * The configuration for each language context is inherited from its parent/creator context. In
 * addition to that {@link Builder#config(String, Object) config} parameters can be passed to new
 * language context instance of the current language. The configuration of other installed languages
 * cannot be modified. To run guest language code in a context, the context needs to be first
 * {@link #enter() entered} and then {@link #leave(Object) left}. The context should be
 * {@link #close() closed} when it is no longer needed. If the context is not closed explicitly,
 * then it is automatically closed together with the parent context.
 * <p>
 * Example usage: {@link TruffleContextSnippets#executeInContext}
 *
 * @since 0.27
 */
public final class TruffleContext implements AutoCloseable {

    static final TruffleContext EMPTY = new TruffleContext();

    private static final ThreadLocal<List<Object>> CONTEXT_ASSERT_STACK;

    static {
        boolean assertions = false;
        assert (assertions = true) == true;
        CONTEXT_ASSERT_STACK = assertions ? new ThreadLocal<List<Object>>() {
            @Override
            protected List<Object> initialValue() {
                return new ArrayList<>();
            }
        } : null;
    }
    final Object polyglotContext;
    final boolean closeable;

    TruffleContext(Object polyglotContext, boolean closeable) {
        this.polyglotContext = polyglotContext;
        this.closeable = closeable;
    }

    /*
     * Constructor necessary for inner builder.
     */
    private TruffleContext() {
        this.polyglotContext = null;
        this.closeable = false;
    }

    /**
     * {@inheritDoc}
     *
     * @since 20.3
     */
    @Override
    @TruffleBoundary
    public boolean equals(Object obj) {
        if (!(obj instanceof TruffleContext)) {
            return false;
        }
        TruffleContext c = (TruffleContext) obj;
        return polyglotContext.equals(c.polyglotContext);
    }

    /**
     * {@inheritDoc}
     *
     * @since 20.3
     */
    @Override
    public int hashCode() {
        return polyglotContext.hashCode();
    }

    /**
     * Get a parent context of this context, if any. This provides the hierarchy of inner contexts.
     *
     * @return a parent context, or <code>null</code> if there is no parent
     * @since 0.30
     */
    @TruffleBoundary
    public TruffleContext getParent() {
        try {
            return LanguageAccessor.engineAccess().getParentContext(polyglotContext);
        } catch (Throwable t) {
            throw Env.engineToLanguageException(t);
        }
    }

    /**
     * Enters this context and returns an object representing the previous context. Calls to enter
     * must be followed by a call to {@link #leave(Object)} in a finally block and the previous
     * context must be passed as an argument. It is allowed to enter a context multiple times from
     * the same thread. If the context is currently not entered by any thread then it is allowed be
     * entered by an arbitrary thread. Entering the context from two or more different threads at
     * the same time is possible, unless one of the loaded languages denies access to the thread, in
     * which case an {@link IllegalStateException} is thrown. The result of the enter function is
     * unspecified and must only be passed to {@link #leave(Object)}. The result value must not be
     * stored permanently.
     * <p>
     * Entering a language context is designed for compilation and is most efficient if the
     * {@link TruffleContext context} instance is compilation final.
     *
     * <p>
     * Example usage: {@link TruffleContextSnippets#executeInContext}
     *
     * @see #leave(Object)
     * @since 0.27
     */
    public Object enter() {
        try {
            Object prev = LanguageAccessor.engineAccess().enterInternalContext(polyglotContext);
            if (CONTEXT_ASSERT_STACK != null) {
                verifyEnter(prev);
            }
            return prev;
        } catch (Throwable t) {
            throw Env.engineToLanguageException(t);
        }
    }

    /**
     * Checks whether the context is entered on the current thread and the context is the currently
     * active context on this thread. There can be multiple contexts {@link #isActive() active} on a
     * single thread, but this method only returns <code>true</code> if it is the top-most context.
     * This method is thread-safe and may be used from multiple threads.
     *
     * @since 20.0
     * @return {@code true} if the context is active, {@code false} otherwise
     */
    public boolean isEntered() {
        try {
            return LanguageAccessor.engineAccess().isContextEntered(polyglotContext);
        } catch (Throwable t) {
            throw Env.engineToLanguageException(t);
        }
    }

    /**
     * Returns <code>true</code> if the context is currently active on the current thread, else
     * <code>false</code>. Checks whether the context has been previously entered by this thread
     * with {@link #enter()} and hasn't been left yet with {@link #leave(java.lang.Object)} methods.
     * Multiple contexts can be active on a single thread. See {@link #isEntered()} for checking
     * whether it is the top-most entered context. This method is thread-safe and may be used from
     * multiple threads.
     *
     * @since 20.3
     */
    public boolean isActive() {
        try {
            return LanguageAccessor.engineAccess().isContextActive(polyglotContext);
        } catch (Throwable t) {
            throw Env.engineToLanguageException(t);
        }
    }

    /**
     * Returns <code>true</code> if the context was closed else <code>false</code>. A context may be
     * closed if {@link #close()} or {@link #closeCancelled(Node, String)} was called previously.
     *
     * @since 20.3
     */
    @TruffleBoundary
    public boolean isClosed() {
        try {
            return LanguageAccessor.engineAccess().isContextClosed(polyglotContext);
        } catch (Throwable t) {
            throw Env.engineToLanguageException(t);
        }
    }

    /**
     * Leaves this context and sets the previous context as the new current context.
     * <p>
     * Leaving a language context is designed for compilation and is most efficient if the
     * {@link TruffleContext context} instance is compilation final.
     *
     * @param prev the previous context returned by {@link #enter()}
     * @see #enter()
     * @since 0.27
     */
    public void leave(Object prev) {
        try {
            if (CONTEXT_ASSERT_STACK != null) {
                verifyLeave(prev);
            }
            LanguageAccessor.engineAccess().leaveInternalContext(polyglotContext, prev);
        } catch (Throwable t) {
            throw Env.engineToLanguageException(t);
        }
    }

    @TruffleBoundary
    private static void verifyEnter(Object prev) {
        assert CONTEXT_ASSERT_STACK != null;
        CONTEXT_ASSERT_STACK.get().add(prev);
    }

    @TruffleBoundary
    private static void verifyLeave(Object prev) {
        assert CONTEXT_ASSERT_STACK != null;
        List<Object> list = CONTEXT_ASSERT_STACK.get();
        assert !list.isEmpty() : "Assert stack is empty.";
        Object expectedPrev = list.get(list.size() - 1);
        assert prev == expectedPrev : "Invalid prev argument provided in TruffleContext.leave(Object).";
        list.remove(list.size() - 1); // pop
    }

    /**
     * Closes this context and disposes its resources. Closes this context and disposes its
     * resources. A context cannot be closed if it is currently {@link #enter() entered} or active
     * by any thread. If a closed context is attempted to be accessed or entered, then an
     * {@link IllegalStateException} is thrown. If the context is not closed explicitly, then it is
     * automatically closed together with the parent context. If an attempt to close a context was
     * successful then consecutive calls to close have no effect.
     *
     * @throws UnsupportedOperationException if the close operation is not supported on this context
     * @throws IllegalStateException if the context is {@link #isActive() active}.
     * @since 0.27
     * @see #closeCancelled(Node, String)
     * @see #closeResourceExhausted(Node, String)
     */
    @Override
    @TruffleBoundary
    public void close() {
        if (!closeable) {
            throw new UnsupportedOperationException("This context instance has no permission to close. " +
                            "Only the original creator of the truffle context or instruments can close.");
        }
        try {
            LanguageAccessor.engineAccess().closeContext(polyglotContext, false, null, false, null);
        } catch (Throwable t) {
            throw Env.engineToLanguageException(t);
        }
    }

    /**
     * Force closes the context as cancelled and stops all the execution on all active threads using
     * a {@link ThreadDeath} exception. If this context is not currently {@link #isEntered()
     * entered} on the current thread then this method waits until the close operation is complete
     * and {@link #isClosed()} returns <code>true</code>, else it throws the cancelled exception
     * upon its completion of this method. If an attempt to close a context was successful then
     * consecutive calls to close have no effect.
     * <p>
     * If forced and this context currently {@link #isEntered() entered} on the current thread and
     * no other context is entered on the current thread then this method directly throws a
     * {@link ThreadDeath} error instead of completing to indicate that the current thread should be
     * stopped. The thrown {@link ThreadDeath} must not be caught by the guest language and freely
     * propagated to the guest application to cancel the execution on the current thread. If a
     * context is {@link #isActive() active} on the current thread, but not {@link #isEntered()
     * entered}, then an {@link IllegalStateException} is thrown, as parent contexts that are active
     * on the current thread cannot be cancelled.
     *
     * @param closeLocation the node where the close occurred. If the context is currently entered
     *            on the thread, then this node will be used as location, for the exception thrown.
     *            If the context is not entered, then the closeLocation parameter will be ignored.
     * @param message exception text for humans provided to the embedder and tools that observe the
     *            cancellation.
     * @throws UnsupportedOperationException if the close operation is not supported on this context
     * @throws IllegalStateException if the context is {@link #isActive() active} but not
     *             {@link #isEntered() entered} on the current thread.
     * @see #close()
     * @see #closeResourceExhausted(Node, String)
     * @since 20.3
     */
    @TruffleBoundary
    public void closeCancelled(Node closeLocation, String message) {
        if (!closeable) {
            throw new UnsupportedOperationException("This context instance has no permission to close. " +
                            "Only the original creator of the truffle context or instruments can close.");
        }
        try {
            LanguageAccessor.engineAccess().closeContext(polyglotContext, true, closeLocation, false, message);
        } catch (Throwable t) {
            throw Env.engineToLanguageException(t);
        }
    }

    /**
     * Force closes the context due to resource exhaustion. This method is equivalent to calling
     * {@link #closeCancelled(Node, String) closeCancelled(location, message)} except in addition
     * the thrown {@link PolyglotException} returns <code>true</code> for
     * {@link PolyglotException#isResourceExhausted()}.
     *
     * @see PolyglotException#isResourceExhausted()
     * @see #close()
     * @see #closeCancelled(Node, String)
     * @since 20.3
     */
    @TruffleBoundary
    public void closeResourceExhausted(Node location, String message) {
        if (!closeable) {
            throw new UnsupportedOperationException("This context instance has no permission to cancel. " +
                            "Only the original creator of the truffle context or instruments can close.");
        }
        try {
            LanguageAccessor.engineAccess().closeContext(polyglotContext, true, location, true, message);
        } catch (Throwable t) {
            throw Env.engineToLanguageException(t);
        }
    }

    /**
     * Builder class to create new {@link TruffleContext} instances.
     *
     * @since 0.27
     */
    public final class Builder {

        private final Env sourceEnvironment;
        private Map<String, Object> config;

        Builder(Env env) {
            this.sourceEnvironment = env;
        }

        /**
         * Sets a config parameter that the child context of this language can access using
         * {@link Env#getConfig()}.
         *
         * @since 0.27
         */
        @TruffleBoundary
        public Builder config(String key, Object value) {
            if (config == null) {
                config = new HashMap<>();
            }
            config.put(key, value);
            return this;
        }

        /**
         * Builds the new context instance.
         *
         * @since 0.27
         */
        @TruffleBoundary
        public TruffleContext build() {
            try {
                return LanguageAccessor.engineAccess().createInternalContext(sourceEnvironment.getPolyglotLanguageContext(), config);
            } catch (Throwable t) {
                throw Env.engineToLanguageException(t);
            }
        }
    }

}

class TruffleContextSnippets {
    // @formatter:off
    abstract class MyContext {
    }
    abstract class MyLanguage extends TruffleLanguage<MyContext> {
    }
    // BEGIN: TruffleContextSnippets#executeInContext
    void executeInContext(Env env) {
        MyContext outerLangContext = getContext();

        TruffleContext innerContext = env.newContextBuilder().build();
        Object p = innerContext.enter();
        try {
            MyContext innerLangContext = getContext();

            assert outerLangContext != innerLangContext;
        } finally {
            innerContext.leave(p);
        }
        assert outerLangContext == getContext();
        innerContext.close();
    }
    private static MyContext getContext() {
        return TruffleLanguage.getCurrentContext(MyLanguage.class);
    }
    // END: TruffleContextSnippets#executeInContext
    // @formatter:on

}
