/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *  
 *    http://www.apache.org/licenses/LICENSE-2.0
 *  
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License. 
 *  
 */
package org.apache.mina.util;

import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * A {@link Map}-backed {@link Set}.
 *
 * @author The Apache MINA Project (dev@mina.apache.org)
 * @version $Rev$, $Date$
 */
public class MapBackedSet<E> extends AbstractSet<E> {
    private final Map<E, Boolean> delegate;

    public MapBackedSet(Map<E, Boolean> map) {
        this.delegate = map;
    }

    public MapBackedSet(Map<E, Boolean> map, Collection<E> c) {
        this. delegate = map;
        addAll(c);
    }

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public boolean contains(Object o) {
        return delegate.containsKey(o);
    }

    @Override
    public Iterator<E> iterator() {
        return delegate.keySet().iterator();
    }

    @Override
    public boolean add(E arg0) {
        return delegate.put(arg0, Boolean.TRUE) == null;
    }

    @Override
    public boolean remove(Object arg0) {
        return delegate.remove(arg0) != null;
    }

    @Override
    public void clear() {
        delegate.clear();
    }
}