//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.util.BufferUtil;

/**
 * The Content abstract is based on what is already used in several places.
 * It allows EOF and Error flows to be unified with content data. This allows
 * the semantics of multiple methods like flush, close, onError, etc. to be
 * included in the read/write APIs.
 */
public interface Content
{
    ByteBuffer getByteBuffer();

    default boolean isSpecial()
    {
        return false;
    }

    default void release()
    {
    }

    default boolean isLast()
    {
        return false;
    }

    default int remaining()
    {
        ByteBuffer b = getByteBuffer();
        return b == null ? 0 : b.remaining();
    }

    default boolean hasRemaining()
    {
        ByteBuffer b = getByteBuffer();
        return b != null && b.hasRemaining();
    }

    default boolean isEmpty()
    {
        return !hasRemaining();
    }

    default int fill(byte[] buffer, int offset, int length)
    {
        ByteBuffer b = getByteBuffer();
        if (b == null || !b.hasRemaining())
            return 0;
        length = Math.min(length, b.remaining());
        b.get(buffer, offset, length);
        return length;
    }

    /**
     * Get the next content if known from the current content
     * @return The next content, which may be null if not known, EOF or the current content if persistent
     */
    default Content next()
    {
        return isSpecial() ? this : isLast() ? Content.EOF : null;
    }

    static Content from(Content content, Content next)
    {
        if (Objects.equals(content.next(), next))
            return content;
        return new Abstract(content.isSpecial(), content.isLast())
        {
            @Override
            public ByteBuffer getByteBuffer()
            {
                return content.getByteBuffer();
            }

            @Override
            public void release()
            {
                content.release();
            }

            @Override
            public Content next()
            {
                if (content.next() == null)
                    return next;
                return from(content.next(), next);
            }
        };
    }

    static Content from(ByteBuffer buffer)
    {
        return () -> buffer;
    }

    static Content from(ByteBuffer buffer, boolean last)
    {
        return new Abstract(false, last)
        {
            @Override
            public ByteBuffer getByteBuffer()
            {
                return buffer;
            }

            @Override
            public String toString()
            {
                return String.format("[%s, l=%b]", BufferUtil.toDetailString(getByteBuffer()), isLast());
            }
        };
    }

    abstract class Abstract implements Content
    {
        private final boolean _special;
        private final boolean _last;

        protected Abstract(boolean special, boolean last)
        {
            _special = special;
            _last = last;
        }

        @Override
        public boolean isSpecial()
        {
            return _special;
        }

        @Override
        public boolean isLast()
        {
            return _last;
        }

        @Override
        public ByteBuffer getByteBuffer()
        {
            return null;
        }
    }

    Content EOF = new Abstract(true, true)
    {
        @Override
        public boolean isLast()
        {
            return true;
        }

        @Override
        public String toString()
        {
            return "EOF";
        }
    };

    class Error extends Abstract
    {
        private final Throwable _cause;

        public Error(Throwable cause)
        {
            super(true, true);
            _cause = cause == null ? new IOException("unknown") : cause;
        }

        Throwable getCause()
        {
            return _cause;
        }

        @Override
        public String toString()
        {
            return _cause.toString();
        }
    }

    class Trailers extends Abstract
    {
        private final HttpFields _trailers;

        public Trailers(HttpFields trailers)
        {
            super(true, true);
            _trailers = trailers;
        }

        public HttpFields getTrailers()
        {
            return _trailers;
        }

        @Override
        public Content next()
        {
            return EOF;
        }

        @Override
        public String toString()
        {
            return "TRAILERS";
        }
    }
}
