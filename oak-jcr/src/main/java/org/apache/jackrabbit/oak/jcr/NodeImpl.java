/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.jcr;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Calendar;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.jcr.AccessDeniedException;
import javax.jcr.Binary;
import javax.jcr.InvalidItemStateException;
import javax.jcr.Item;
import javax.jcr.ItemExistsException;
import javax.jcr.ItemNotFoundException;
import javax.jcr.ItemVisitor;
import javax.jcr.NoSuchWorkspaceException;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.PathNotFoundException;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.UnsupportedRepositoryOperationException;
import javax.jcr.Value;
import javax.jcr.Workspace;
import javax.jcr.lock.Lock;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.nodetype.NodeDefinition;
import javax.jcr.nodetype.NodeType;
import javax.jcr.nodetype.NodeTypeManager;
import javax.jcr.version.Version;
import javax.jcr.version.VersionException;
import javax.jcr.version.VersionHistory;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import org.apache.jackrabbit.api.JackrabbitNode;
import org.apache.jackrabbit.commons.ItemNameMatcher;
import org.apache.jackrabbit.commons.iterator.NodeIteratorAdapter;
import org.apache.jackrabbit.commons.iterator.PropertyIteratorAdapter;
import org.apache.jackrabbit.oak.api.CommitFailedException;
import org.apache.jackrabbit.oak.api.ContentSession;
import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.api.Root;
import org.apache.jackrabbit.oak.api.Tree;
import org.apache.jackrabbit.oak.api.Tree.Status;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.commons.PathUtils;
import org.apache.jackrabbit.oak.core.IdentifierManager;
import org.apache.jackrabbit.oak.jcr.delegate.NodeDelegate;
import org.apache.jackrabbit.oak.jcr.delegate.PropertyDelegate;
import org.apache.jackrabbit.oak.jcr.operation.NodeOperation;
import org.apache.jackrabbit.oak.plugins.memory.PropertyStates;
import org.apache.jackrabbit.oak.plugins.nodetype.EffectiveNodeType;
import org.apache.jackrabbit.oak.spi.security.authorization.permission.Permissions;
import org.apache.jackrabbit.oak.util.TODO;
import org.apache.jackrabbit.value.ValueHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Collections.singleton;
import static javax.jcr.Property.JCR_LOCK_IS_DEEP;
import static javax.jcr.Property.JCR_LOCK_OWNER;
import static org.apache.jackrabbit.JcrConstants.JCR_MIXINTYPES;
import static org.apache.jackrabbit.JcrConstants.JCR_PRIMARYTYPE;
import static org.apache.jackrabbit.oak.api.Type.BOOLEAN;
import static org.apache.jackrabbit.oak.api.Type.NAME;
import static org.apache.jackrabbit.oak.api.Type.NAMES;

/**
 * TODO document
 *
 * @param <T> the delegate type
 */
public class NodeImpl<T extends NodeDelegate> extends ItemImpl<T> implements Node, JackrabbitNode {

    /**
     * logger instance
     */
    private static final Logger log = LoggerFactory.getLogger(NodeImpl.class);

    public NodeImpl(T dlg, SessionContext sessionContext) {
        super(dlg, sessionContext);
    }

    //---------------------------------------------------------------< Item >---

    /**
     * @see javax.jcr.Item#isNode()
     */
    @Override
    public boolean isNode() {
        return true;
    }

    /**
     * @see javax.jcr.Item#getParent()
     */
    @Override
    @Nonnull
    public Node getParent() throws RepositoryException {
        return perform(new NodeOperation<Node>(dlg) {
            @Override
            public Node perform() throws RepositoryException {
                if (node.isRoot()) {
                    throw new ItemNotFoundException("Root has no parent");
                } else {
                    NodeDelegate parent = node.getParent();
                    if (parent == null) {
                        throw new AccessDeniedException();
                    }
                    return sessionContext.createNodeOrNull(parent);
                }
            }
        });
    }

    /**
     * @see javax.jcr.Item#isNew()
     */
    @Override
    public boolean isNew() {
        return safePerform(new NodeOperation<Boolean>(dlg) {
            @Override
            public Boolean perform() {
                return node.exists() && node.getStatus() == Status.NEW;
            }
        });
    }

    /**
     * @see javax.jcr.Item#isModified()
     */
    @Override
    public boolean isModified() {
        return safePerform(new NodeOperation<Boolean>(dlg) {
            @Override
            public Boolean perform() {
                return node.exists() && node.getStatus() == Status.MODIFIED;
            }
        });
    }

    /**
     * @see javax.jcr.Item#remove()
     */
    @Override
    public void remove() throws RepositoryException {
        perform(new ItemWriteOperation<Void>() {
            @Override
            public Void perform() throws RepositoryException {
                if (dlg.isRoot()) {
                    throw new RepositoryException("Cannot remove the root node");
                }

                dlg.remove();
                return null;
            }
        });
    }

    @Override
    public void accept(ItemVisitor visitor) throws RepositoryException {
        visitor.visit(this);
    }

    //---------------------------------------------------------------< Node >---

    /**
     * @see Node#addNode(String)
     */
    @Override
    @Nonnull
    public Node addNode(String relPath) throws RepositoryException {
        return addNode(relPath, null);
    }

    @Override @Nonnull
    public Node addNode(final String relPath, String primaryNodeTypeName)
            throws RepositoryException {
        final String oakPath = getOakPathOrThrowNotFound(relPath);
        final String oakTypeName;
        if (primaryNodeTypeName != null) {
            oakTypeName = getOakName(primaryNodeTypeName);
        } else {
            oakTypeName = null;
        }

        SessionImpl.checkIndexOnName(sessionContext, relPath);
        return perform(new ItemWriteOperation<Node>() {
            @Override
            public Node perform() throws RepositoryException {
                String oakName = PathUtils.getName(oakPath);
                String parentPath = PathUtils.getParentPath(oakPath);

                NodeDelegate parent = dlg.getChild(parentPath);
                if (parent == null) {
                    // is it a property?
                    String grandParentPath = PathUtils.getParentPath(parentPath);
                    NodeDelegate grandParent = dlg.getChild(grandParentPath);
                    if (grandParent != null) {
                        String propName = PathUtils.getName(parentPath);
                        if (grandParent.getPropertyOrNull(propName) != null) {
                            throw new ConstraintViolationException("Can't add new node to property.");
                        }
                    }

                    throw new PathNotFoundException(relPath);
                }

                if (parent.getChild(oakName) != null) {
                    throw new ItemExistsException(relPath);
                }

                // check for NODE_TYPE_MANAGEMENT permission here as we cannot
                // distinguish between user-supplied and system-generated
                // modification of that property in the PermissionValidator
                if (oakTypeName != null) {
                    PropertyState prop = PropertyStates.createProperty(JCR_PRIMARYTYPE, oakTypeName, NAME);
                    sessionContext.getAccessManager().checkPermissions(dlg.getTree(), prop, Permissions.NODE_TYPE_MANAGEMENT);
                }

                NodeDelegate added = parent.addChild(oakName, oakTypeName);
                if (added == null) {
                    throw new ItemExistsException();
                }
                return sessionContext.createNodeOrNull(added);
            }
        });
    }

    @Override
    public void orderBefore(final String srcChildRelPath, final String destChildRelPath) throws RepositoryException {
        perform(new ItemWriteOperation<Void>() {
            @Override
            public Void perform() throws RepositoryException {
                getEffectiveNodeType().checkOrderableChildNodes();
                String oakSrcChildRelPath = getOakPathOrThrowNotFound(srcChildRelPath);
                String oakDestChildRelPath = null;
                if (destChildRelPath != null) {
                    oakDestChildRelPath = getOakPathOrThrowNotFound(destChildRelPath);
                }
                dlg.orderBefore(oakSrcChildRelPath, oakDestChildRelPath);
                return null;
            }
        });
    }

    //-------------------------------------------------------< setProperty >--
    //
    // The setProperty() variants below follow the same pattern:
    //
    //     if (value != null) {
    //         return internalSetProperty(name, ...);
    //     } else {
    //         return internalRemoveProperty(name);
    //     }
    //
    // In addition the value and value type information is pre-processed
    // according to the method signature before being passed to
    // internalSetProperty(). The methods that take a non-nullable
    // primitive value as an argument can skip the if clause.
    //
    // Note that due to backwards compatibility reasons (OAK-395) none
    // of the methods will ever return null, even if asked to remove
    // a non-existing property! See internalRemoveProperty() for details.

    @Override @Nonnull
    public Property setProperty(String name, Value value)
            throws RepositoryException {
        if (value != null) {
            return internalSetProperty(name, value, false);
        } else {
            return internalRemoveProperty(name);
        }
    }

    @Override @Nonnull
    public Property setProperty(String name, Value value, int type)
            throws RepositoryException {
        if (value != null) {
            boolean exactTypeMatch = true;
            if (type == PropertyType.UNDEFINED) {
                type = value.getType();
                exactTypeMatch = false;
            } else {
                value = ValueHelper.convert(value, type, getValueFactory());
            }
            return internalSetProperty(name, value, exactTypeMatch);
        } else {
            return internalRemoveProperty(name);
        }
    }

    @Override @Nonnull
    public Property setProperty(String name, Value[] values)
            throws RepositoryException {
        if (values != null) {
            // TODO: type
            return internalSetProperty(name, values, ValueHelper.getType(values), false);
        } else {
            return internalRemoveProperty(name);
        }
    }

    @Override @Nonnull
    public Property setProperty(String jcrName, Value[] values, int type)
            throws RepositoryException {
        if (values != null) {
            boolean exactTypeMatch = true;
            if (type == PropertyType.UNDEFINED) {
                type = PropertyType.STRING;
                exactTypeMatch = false;
            }
            values = ValueHelper.convert(values, type, getValueFactory());
            return internalSetProperty(jcrName, values, type, exactTypeMatch);
        } else {
            return internalRemoveProperty(jcrName);
        }
    }

    @Override @Nonnull
    public Property setProperty(String name, String[] values)
            throws RepositoryException {
        if (values != null) {
            int type = PropertyType.STRING;
            Value[] vs = ValueHelper.convert(values, type, getValueFactory());
            return internalSetProperty(name, vs, type, false);
        } else {
            return internalRemoveProperty(name);
        }
    }

    @Override @Nonnull
    public Property setProperty(String name, String[] values, int type)
            throws RepositoryException {
        if (values != null) {
            boolean exactTypeMatch = true;
            if (type == PropertyType.UNDEFINED) {
                type = PropertyType.STRING;
                exactTypeMatch = false;
            }
            Value[] vs = ValueHelper.convert(values, type, getValueFactory());
            return internalSetProperty(name, vs, type, exactTypeMatch);
        } else {
            return internalRemoveProperty(name);
        }
    }

    @Override @Nonnull
    public Property setProperty(String name, String value)
            throws RepositoryException {
        if (value != null) {
            Value v = getValueFactory().createValue(value);
            return internalSetProperty(name, v, false);
        } else {
            return internalRemoveProperty(name);
        }
    }

    @Override @Nonnull
    public Property setProperty(String name, String value, int type)
            throws RepositoryException {
        if (value != null) {
            boolean exactTypeMatch = true;
            if (type == PropertyType.UNDEFINED) {
                type = PropertyType.STRING;
                exactTypeMatch = false;
            }
            Value v = getValueFactory().createValue(value, type);
            return internalSetProperty(name, v, exactTypeMatch);
        } else {
            return internalRemoveProperty(name);
        }
    }

    @Override @Nonnull @SuppressWarnings("deprecation")
    public Property setProperty(String name, InputStream value)
            throws RepositoryException {
        if (value != null) {
            Value v = getValueFactory().createValue(value);
            return internalSetProperty(name, v, false);
        } else {
            return internalRemoveProperty(name);
        }
    }

    @Override @Nonnull
    public Property setProperty(String name, Binary value)
            throws RepositoryException {
        if (value != null) {
            Value v = getValueFactory().createValue(value);
            return internalSetProperty(name, v, false);
        } else {
            return internalRemoveProperty(name);
        }
    }

    @Override @Nonnull
    public Property setProperty(String name, boolean value)
            throws RepositoryException {
        Value v = getValueFactory().createValue(value);
        return internalSetProperty(name, v, false);
    }

    @Override @Nonnull
    public Property setProperty(String name, double value)
            throws RepositoryException {
        Value v = getValueFactory().createValue(value);
        return internalSetProperty(name, v, false);
    }

    @Override @Nonnull
    public Property setProperty(String name, BigDecimal value)
            throws RepositoryException {
        if (value != null) {
            Value v = getValueFactory().createValue(value);
            return internalSetProperty(name, v, false);
        } else {
            return internalRemoveProperty(name);
        }
    }

    @Override @Nonnull
    public Property setProperty(String name, long value)
            throws RepositoryException {
        Value v = getValueFactory().createValue(value);
        return internalSetProperty(name, v, false);
    }

    @Override @Nonnull
    public Property setProperty(String name, Calendar value)
            throws RepositoryException {
        if (value != null) {
            Value v = getValueFactory().createValue(value);
            return internalSetProperty(name, v, false);
        } else {
            return internalRemoveProperty(name);
        }
    }

    @Override @Nonnull
    public Property setProperty(String name, Node value)
            throws RepositoryException {
        if (value != null) {
            Value v = getValueFactory().createValue(value);
            return internalSetProperty(name, v, false);
        } else {
            return internalRemoveProperty(name);
        }
    }

    @Override
    @Nonnull
    public Node getNode(String relPath) throws RepositoryException {
        final String oakPath = getOakPathOrThrowNotFound(relPath);
        return perform(new NodeOperation<Node>(dlg) {
            @Override
            public Node perform() throws RepositoryException {
                NodeDelegate nd = node.getChild(oakPath);
                if (nd == null) {
                    throw new PathNotFoundException(oakPath);
                } else {
                    return sessionContext.createNodeOrNull(nd);
                }
            }
        });
    }

    @Override
    @Nonnull
    public NodeIterator getNodes() throws RepositoryException {
        return perform(new NodeOperation<NodeIterator>(dlg) {
            @Override
            public NodeIterator perform() throws RepositoryException {
                Iterator<NodeDelegate> children = node.getChildren();
                return new NodeIteratorAdapter(nodeIterator(children)) {
                    private long size = -1;
                    @Override
                    public long getSize() {
                        if (size == -1) {
                            try {
                                size = node.getChildCount(); // TODO: perform()
                            } catch (InvalidItemStateException e) {
                                throw new IllegalStateException(
                                        "This iterator is no longer valid", e);
                            }
                        }
                        return size;
                    }
                };
            }
        });
    }

    @Override
    @Nonnull
    public NodeIterator getNodes(final String namePattern)
            throws RepositoryException {
        return perform(new NodeOperation<NodeIterator>(dlg) {
            @Override
            public NodeIterator perform() throws RepositoryException {
                Iterator<NodeDelegate> children = Iterators.filter(
                        node.getChildren(),
                        new Predicate<NodeDelegate>() {
                            @Override
                            public boolean apply(NodeDelegate state) {
                                // TODO: use Oak names
                                return ItemNameMatcher.matches(toJcrPath(state.getName()), namePattern);
                            }
                        });
                return new NodeIteratorAdapter(nodeIterator(children));
            }
        });
    }

    @Override
    @Nonnull
    public NodeIterator getNodes(final String[] nameGlobs) throws RepositoryException {
        return perform(new NodeOperation<NodeIterator>(dlg) {
            @Override
            public NodeIterator perform() throws RepositoryException {
                Iterator<NodeDelegate> children = Iterators.filter(
                        node.getChildren(),
                        new Predicate<NodeDelegate>() {
                            @Override
                            public boolean apply(NodeDelegate state) {
                                // TODO: use Oak names
                                return ItemNameMatcher.matches(toJcrPath(state.getName()), nameGlobs);
                            }
                        });
                return new NodeIteratorAdapter(nodeIterator(children));
            }
        });
    }

    @Override
    @Nonnull
    public Property getProperty(String relPath) throws RepositoryException {
        final String oakPath = getOakPathOrThrowNotFound(relPath);
        return perform(new NodeOperation<PropertyImpl>(dlg) {
            @Override
            public PropertyImpl perform() throws RepositoryException {
                PropertyDelegate pd = node.getPropertyOrNull(oakPath);
                if (pd == null) {
                    throw new PathNotFoundException(
                            oakPath + " not found on " + node.getPath());
                } else {
                    return new PropertyImpl(pd, sessionContext);
                }
            }
        });
    }

    @Override
    @Nonnull
    public PropertyIterator getProperties() throws RepositoryException {
        return perform(new NodeOperation<PropertyIterator>(dlg) {
            @Override
            public PropertyIterator perform() throws RepositoryException {
                Iterator<PropertyDelegate> properties = node.getProperties();
                long size = node.getPropertyCount();
                return new PropertyIteratorAdapter(
                        propertyIterator(properties), size);
            }
        });
    }

    @Override
    @Nonnull
    public PropertyIterator getProperties(final String namePattern) throws RepositoryException {
        return perform(new NodeOperation<PropertyIterator>(dlg) {
            @Override
            public PropertyIterator perform() throws RepositoryException {
                Iterator<PropertyDelegate> properties = Iterators.filter(
                        node.getProperties(),
                        new Predicate<PropertyDelegate>() {
                            @Override
                            public boolean apply(PropertyDelegate entry) {
                                // TODO: use Oak names
                                return ItemNameMatcher.matches(toJcrPath(entry.getName()), namePattern);
                            }
                        });
                return new PropertyIteratorAdapter(propertyIterator(properties));
            }
        });
    }

    @Override
    @Nonnull
    public PropertyIterator getProperties(final String[] nameGlobs) throws RepositoryException {
        return perform(new NodeOperation<PropertyIterator>(dlg) {
            @Override
            public PropertyIterator perform() throws RepositoryException {
                Iterator<PropertyDelegate> propertyNames = Iterators.filter(
                        node.getProperties(),
                        new Predicate<PropertyDelegate>() {
                            @Override
                            public boolean apply(PropertyDelegate entry) {
                                // TODO: use Oak names
                                return ItemNameMatcher.matches(toJcrPath(entry.getName()), nameGlobs);
                            }
                        });
                return new PropertyIteratorAdapter(propertyIterator(propertyNames));
            }
        });
    }

    /**
     * @see javax.jcr.Node#getPrimaryItem()
     */
    @Override
    @Nonnull
    public Item getPrimaryItem() throws RepositoryException {
        return perform(new NodeOperation<Item>(dlg) {
            @Override
            public Item perform() throws RepositoryException {
                // TODO: avoid nested calls
                String name = getPrimaryNodeType().getPrimaryItemName();
                if (name == null) {
                    throw new ItemNotFoundException("No primary item present on node " + this);
                }
                if (hasProperty(name)) {
                    return getProperty(name);
                } else if (hasNode(name)) {
                    return getNode(name);
                } else {
                    throw new ItemNotFoundException("Primary item " + name + " does not exist on node " + this);
                }
            }
        });
    }

    /**
     * @see javax.jcr.Node#getUUID()
     */
    @Override
    @Nonnull
    public String getUUID() throws RepositoryException {
        return perform(new NodeOperation<String>(dlg) {
            @Override
            public String perform() throws RepositoryException {
                // TODO: avoid nested calls
                if (isNodeType(NodeType.MIX_REFERENCEABLE)) {
                    return getIdentifier();
                }
                throw new UnsupportedRepositoryOperationException("Node is not referenceable.");
            }
        });
    }

    @Override
    @Nonnull
    public String getIdentifier() throws RepositoryException {
        // TODO: name mapping for path identifiers
        return perform(new NodeOperation<String>(dlg) {
            @Override
            public String perform() throws RepositoryException {
                return node.getIdentifier();
            }
        });
    }

    @Override
    public int getIndex() throws RepositoryException {
        // as long as we do not support same name siblings, index always is 1
        return 1; // TODO
    }

    private PropertyIterator internalGetReferences(final String name, final boolean weak) throws RepositoryException {
        return perform(new NodeOperation<PropertyIterator>(dlg) {
            @Override
            public PropertyIterator perform() throws InvalidItemStateException {
                IdentifierManager idManager = sessionDelegate.getIdManager();

                Set<String> propertyOakPaths = idManager.getReferences(weak, node.getTree(), name); // TODO: oak name?
                Iterable<Property> properties = Iterables.transform(
                        propertyOakPaths,
                        new Function<String, Property>() {
                            @Override
                            public Property apply(String oakPath) {
                                PropertyDelegate pd = sessionDelegate.getProperty(oakPath);
                                return pd == null ? null : new PropertyImpl(pd, sessionContext);
                            }
                        }
                );

                return new PropertyIteratorAdapter(properties.iterator(), propertyOakPaths.size());
            }
        });
    }

    /**
     * @see javax.jcr.Node#getReferences()
     */
    @Override
    @Nonnull
    public PropertyIterator getReferences() throws RepositoryException {
        return internalGetReferences(null, false);
    }

    @Override
    @Nonnull
    public PropertyIterator getReferences(final String name) throws RepositoryException {
        return internalGetReferences(name, false);
    }

    /**
     * @see javax.jcr.Node#getWeakReferences()
     */
    @Override
    @Nonnull
    public PropertyIterator getWeakReferences() throws RepositoryException {
        return internalGetReferences(null, true);
    }

    @Override
    @Nonnull
    public PropertyIterator getWeakReferences(String name) throws RepositoryException {
        return internalGetReferences(name, true);
    }

    @Override
    public boolean hasNode(String relPath) throws RepositoryException {
        final String oakPath = getOakPathOrThrow(relPath);
        return perform(new NodeOperation<Boolean>(dlg) {
            @Override
            public Boolean perform() throws RepositoryException {
                return node.getChild(oakPath) != null;
            }
        });
    }

    @Override
    public boolean hasProperty(String relPath) throws RepositoryException {
        final String oakPath = getOakPathOrThrow(relPath);
        return perform(new NodeOperation<Boolean>(dlg) {
            @Override
            public Boolean perform() throws RepositoryException {
                return node.getPropertyOrNull(oakPath) != null;
            }
        });
    }

    @Override
    public boolean hasNodes() throws RepositoryException {
        return getNodes().hasNext();
    }

    @Override
    public boolean hasProperties() throws RepositoryException {
        return perform(new NodeOperation<Boolean>(dlg) {
            @Override
            public Boolean perform() throws RepositoryException {
                return node.getPropertyCount() != 0;
            }
        });
    }

    /**
     * @see javax.jcr.Node#getPrimaryNodeType()
     */
    @Override
    @Nonnull
    public NodeType getPrimaryNodeType() throws RepositoryException {
        return perform(new NodeOperation<NodeType>(dlg) {
            @Override
            public NodeType perform() throws RepositoryException {
                // TODO: avoid nested calls
                String primaryNtName;
                if (hasProperty(Property.JCR_PRIMARY_TYPE)) {
                    primaryNtName = getProperty(Property.JCR_PRIMARY_TYPE).getString();
                } else {
                    throw new RepositoryException("Node " + getPath() + " doesn't have primary type set.");
                }
                return getNodeTypeManager().getNodeType(primaryNtName);
            }
        });
    }

    /**
     * @see javax.jcr.Node#getMixinNodeTypes()
     */
    @Override
    @Nonnull
    public NodeType[] getMixinNodeTypes() throws RepositoryException {
        return perform(new NodeOperation<NodeType[]>(dlg) {
            @Override
            public NodeType[] perform() throws RepositoryException {
                // TODO: avoid nested calls
                // TODO: check if transient changes to mixin-types are reflected here
                if (hasProperty(Property.JCR_MIXIN_TYPES)) {
                    NodeTypeManager ntMgr = getNodeTypeManager();
                    Value[] mixinNames = getProperty(Property.JCR_MIXIN_TYPES).getValues();
                    NodeType[] mixinTypes = new NodeType[mixinNames.length];
                    for (int i = 0; i < mixinNames.length; i++) {
                        mixinTypes[i] = ntMgr.getNodeType(mixinNames[i].getString());
                    }
                    return mixinTypes;
                } else {
                    return new NodeType[0];
                }
            }
        });
    }

    @Override
    public boolean isNodeType(String nodeTypeName) throws RepositoryException {
        final String oakName = getOakName(nodeTypeName);
        return perform(new NodeOperation<Boolean>(dlg) {
            @Override
            public Boolean perform() throws RepositoryException {
                return getEffectiveNodeTypeProvider().isNodeType(
                        node.getTree(), oakName);
            }
        });
    }

    @Override
    public void setPrimaryType(final String nodeTypeName) throws RepositoryException {
        perform(new ItemWriteOperation<Void>() {
            @Override
            public void checkPreconditions() throws RepositoryException {
                super.checkPreconditions();
                if (!isCheckedOut()) {
                    throw new VersionException("Cannot set primary type. Node is checked in.");
                }
            }

            @Override
            public Void perform() throws RepositoryException {
                internalSetPrimaryType(nodeTypeName);
                return null;
            }
        });
    }

    @Override
    public void addMixin(String mixinName) throws RepositoryException {
        final String oakTypeName = getOakName(checkNotNull(mixinName));
        perform(new ItemWriteOperation<Void>() {
            @Override
            public void checkPreconditions() throws RepositoryException {
                super.checkPreconditions();
                if (!isCheckedOut()) {
                    throw new VersionException(
                            "Cannot add mixin type. Node is checked in.");
                }
            }
            @Override
            public Void perform() throws RepositoryException {
                dlg.addMixin(oakTypeName);
                return null;
            }
        });
    }

    @Override
    public void removeMixin(String mixinName) throws RepositoryException {
        final String oakTypeName = getOakName(checkNotNull(mixinName));
        perform(new ItemWriteOperation<Void>() {
            @Override
            public void checkPreconditions() throws RepositoryException {
                super.checkPreconditions();
                if (!isCheckedOut()) {
                    throw new VersionException(
                            "Cannot remove mixin type. Node is checked in.");
                }
            }
            @Override
            public Void perform() throws RepositoryException {
                dlg.removeMixin(oakTypeName);
                return null;
            }
        });
    }

    @Override
    public boolean canAddMixin(String mixinName) throws RepositoryException {
        final String oakTypeName = getOakName(mixinName);
        return perform(new NodeOperation<Boolean>(dlg) {
            @Override
            public Boolean perform() throws RepositoryException {
                PropertyState prop = PropertyStates.createProperty(JCR_MIXINTYPES, singleton(oakTypeName), NAMES);
                return sessionContext.getAccessManager().hasPermissions(
                    node.getTree(), prop, Permissions.NODE_TYPE_MANAGEMENT)
                        && !node.isProtected()
                        && sessionContext.getVersionManager().isCheckedOut(toJcrPath(dlg.getPath())) // TODO: avoid nested calls
                        && node.canAddMixin(oakTypeName);
            }
        });
    }

    @Override
    @Nonnull
    public NodeDefinition getDefinition() throws RepositoryException {
        return perform(new NodeOperation<NodeDefinition>(dlg) {
            @Override
            public NodeDefinition perform() throws RepositoryException {
                NodeDelegate parent = node.getParent();
                if (parent == null) {
                    return getDefinitionProvider().getRootDefinition();
                } else {
                    return getDefinitionProvider().getDefinition(
                            parent.getTree(), node.getTree());
                }
            }
        });
    }

    @Override
    @Nonnull
    public String getCorrespondingNodePath(String workspaceName) throws RepositoryException {
        // TODO: use perform()
        checkValidWorkspace(workspaceName);
        throw new UnsupportedRepositoryOperationException("TODO: Node.getCorrespondingNodePath");
    }


    @Override
    public void update(String srcWorkspace) throws RepositoryException {
        // TODO: use perform()
        checkValidWorkspace(srcWorkspace);

        // check for pending changes
        if (sessionDelegate.hasPendingChanges()) {
            String msg = "Unable to perform operation. Session has pending changes.";
            log.debug(msg);
            throw new InvalidItemStateException(msg);
        }

        // TODO
    }

    /**
     * @see javax.jcr.Node#checkin()
     */
    @Override
    @Nonnull
    public Version checkin() throws RepositoryException {
        return getVersionManager().checkin(getPath());
    }

    /**
     * @see javax.jcr.Node#checkout()
     */
    @Override
    public void checkout() throws RepositoryException {
        getVersionManager().checkout(getPath());
    }

    /**
     * @see javax.jcr.Node#doneMerge(javax.jcr.version.Version)
     */
    @Override
    public void doneMerge(Version version) throws RepositoryException {
        getVersionManager().doneMerge(getPath(), version);
    }

    /**
     * @see javax.jcr.Node#cancelMerge(javax.jcr.version.Version)
     */
    @Override
    public void cancelMerge(Version version) throws RepositoryException {
        getVersionManager().cancelMerge(getPath(), version);
    }

    /**
     * @see javax.jcr.Node#merge(String, boolean)
     */
    @Override
    @Nonnull
    public NodeIterator merge(String srcWorkspace, boolean bestEffort) throws RepositoryException {
        return getVersionManager().merge(getPath(), srcWorkspace, bestEffort);
    }

    /**
     * @see javax.jcr.Node#isCheckedOut()
     */
    @Override
    public boolean isCheckedOut() throws RepositoryException {
        try {
            return getVersionManager().isCheckedOut(getPath());
        } catch (UnsupportedRepositoryOperationException ex) {
            // when versioning is not supported all nodes are considered to be
            // checked out
            return true;
        }
    }

    /**
     * @see javax.jcr.Node#restore(String, boolean)
     */
    @Override
    public void restore(String versionName, boolean removeExisting) throws RepositoryException {
        if (!isNodeType(NodeType.MIX_VERSIONABLE)) {
            throw new UnsupportedRepositoryOperationException("Node is not mix:versionable");
        }
        getVersionManager().restore(getPath(), versionName, removeExisting);
    }

    /**
     * @see javax.jcr.Node#restore(javax.jcr.version.Version, boolean)
     */
    @Override
    public void restore(Version version, boolean removeExisting) throws RepositoryException {
        if (!isNodeType(NodeType.MIX_VERSIONABLE)) {
            throw new UnsupportedRepositoryOperationException("Node is not mix:versionable");
        }
        String id = version.getContainingHistory().getVersionableIdentifier();
        if (getIdentifier().equals(id)) {
            getVersionManager().restore(version, removeExisting);
        } else {
            throw new VersionException("Version does not belong to the " +
                    "VersionHistory of this node.");
        }
    }

    /**
     * @see javax.jcr.Node#restore(Version, String, boolean)
     */
    @Override
    public void restore(Version version, String relPath, boolean removeExisting) throws RepositoryException {
        // additional checks are performed with subsequent calls.
        if (hasNode(relPath)) {
            // node at 'relPath' exists -> call restore on the target Node
            getNode(relPath).restore(version, removeExisting);
        } else {
            String absPath = PathUtils.concat(getPath(), relPath);
            getVersionManager().restore(absPath, version, removeExisting);
        }
    }

    /**
     * @see javax.jcr.Node#restoreByLabel(String, boolean)
     */
    @Override
    public void restoreByLabel(String versionLabel, boolean removeExisting) throws RepositoryException {
        getVersionManager().restoreByLabel(getPath(), versionLabel, removeExisting);
    }

    /**
     * @see javax.jcr.Node#getVersionHistory()
     */
    @Override
    @Nonnull
    public VersionHistory getVersionHistory() throws RepositoryException {
        return getVersionManager().getVersionHistory(getPath());
    }

    /**
     * @see javax.jcr.Node#getBaseVersion()
     */
    @Override
    @Nonnull
    public Version getBaseVersion() throws RepositoryException {
        return getVersionManager().getBaseVersion(getPath());
    }

    /**
     * Checks whether this node is locked by looking for the
     * {@code jcr:lockOwner} property either on this node or
     * on any ancestor that also has the {@code jcr:lockIsDeep}
     * property set to {@code true}.
     */
    @Override
    public boolean isLocked() throws RepositoryException {
        final String lockOwner = getOakPathOrThrow(JCR_LOCK_OWNER);
        final String lockIsDeep = getOakPathOrThrow(JCR_LOCK_IS_DEEP);
        return perform(new NodeOperation<Boolean>(dlg) {
            @Override
            public Boolean perform() throws RepositoryException {
                if (node.getPropertyOrNull(lockOwner) != null) {
                    return true;
                }

                NodeDelegate parent = node.getParent();
                while (parent != null) {
                    if (parent.getPropertyOrNull(lockOwner) != null) {
                        PropertyDelegate isDeep =
                                parent.getPropertyOrNull(lockIsDeep);
                        if (isDeep != null) {
                            PropertyState state = isDeep.getPropertyState();
                            if (!state.isArray() && state.getValue(BOOLEAN)) {
                                return true;
                            }
                        }
                    }
                    parent = parent.getParent();
                }

                return false;
            }
        });
    }

    /**
     * Checks whether this node holds a lock by looking for the
     * {@code jcr:lockOwner} property.
     */
    @Override
    public boolean holdsLock() throws RepositoryException {
        final String lockOwner = getOakPathOrThrow(JCR_LOCK_OWNER);
        return perform(new NodeOperation<Boolean>(dlg) {
            @Override
            public Boolean perform() throws RepositoryException {
                return node.getPropertyOrNull(lockOwner) != null;
            }
        });
    }

    /**
     * @see javax.jcr.Node#getLock()
     */
    @Override
    @Nonnull
    public Lock getLock() throws RepositoryException {
        throw new UnsupportedRepositoryOperationException();
    }

    /**
     * @see javax.jcr.Node#lock(boolean, boolean)
     */
    @Override
    @Nonnull
    public Lock lock(final boolean isDeep, boolean isSessionScoped)
            throws RepositoryException {
        // TODO: use perform()
        ContentSession session = sessionDelegate.getContentSession();
        final String userID = session.getAuthInfo().getUserID();

        String lockOwner = getOakPathOrThrow(JCR_LOCK_OWNER);
        String lockIsDeep = getOakPathOrThrow(JCR_LOCK_IS_DEEP);
        try {
            Root root = session.getLatestRoot();
            Tree tree = root.getTree(dlg.getPath());
            if (!tree.exists()) {
                throw new ItemNotFoundException();
            }
            tree.setProperty(lockOwner, userID);
            tree.setProperty(lockIsDeep, isDeep);
            root.commit(); // TODO: fail instead?
        } catch (CommitFailedException e) {
            throw new RepositoryException("Unable to lock " + this, e);
        }

        getSession().refresh(true);

        if (isSessionScoped) {
            return TODO.dummyImplementation().returnValue(new Lock() {
                @Override
                public String getLockOwner() {
                    return userID;
                }

                @Override
                public boolean isDeep() {
                    return isDeep;
                }

                @Override
                public Node getNode() {
                    return NodeImpl.this;
                }

                @Override
                public String getLockToken() {
                    return null;
                }

                @Override
                public long getSecondsRemaining() {
                    return Long.MAX_VALUE;
                }

                @Override
                public boolean isLive() {
                    return true;
                }

                @Override
                public boolean isSessionScoped() {
                    return true;
                }

                @Override
                public boolean isLockOwningSession() {
                    return true;
                }

                @Override
                public void refresh() {
                }
            });
        }

        return getLock();
    }

    /**
     * @see javax.jcr.Node#unlock()
     */
    @Override
    public void unlock() throws RepositoryException {
        // TODO: use perform
        String lockOwner = getOakPathOrThrow(JCR_LOCK_OWNER);
        String lockIsDeep = getOakPathOrThrow(JCR_LOCK_IS_DEEP);
        try {
            Root root = sessionDelegate.getContentSession().getLatestRoot();
            Tree tree = root.getTree(dlg.getPath());
            if (!tree.exists()) {
                throw new ItemNotFoundException();
            }
            tree.removeProperty(lockOwner);
            tree.removeProperty(lockIsDeep);
            root.commit();
        } catch (CommitFailedException e) {
            throw new RepositoryException("Unable to unlock " + this, e);
        }

        getSession().refresh(true);
    }

    @Override @Nonnull
    public NodeIterator getSharedSet() {
        return new NodeIteratorAdapter(singleton(this));
    }

    @Override
    public void removeSharedSet() throws RepositoryException {
        perform(new ItemWriteOperation<Void>() {
            @Override
            public Void perform() throws RepositoryException {
                // TODO: avoid nested calls
                NodeIterator sharedSet = getSharedSet();
                while (sharedSet.hasNext()) {
                    sharedSet.nextNode().removeShare();
                }
                return null;
            }
        });
    }

    @Override
    public void removeShare() throws RepositoryException {
        remove();
    }

    /**
     * @see javax.jcr.Node#followLifecycleTransition(String)
     */
    @Override
    public void followLifecycleTransition(String transition) throws RepositoryException {
        throw new UnsupportedRepositoryOperationException("Lifecycle Management is not supported");
    }

    /**
     * @see javax.jcr.Node#getAllowedLifecycleTransistions()
     */
    @Override
    @Nonnull
    public String[] getAllowedLifecycleTransistions() throws RepositoryException {
        throw new UnsupportedRepositoryOperationException("Lifecycle Management is not supported");

    }

    //------------------------------------------------------------< internal >---

    private EffectiveNodeType getEffectiveNodeType() throws RepositoryException {
        return getEffectiveNodeTypeProvider().getEffectiveNodeType(dlg.getTree());
    }

    private Iterator<Node> nodeIterator(Iterator<NodeDelegate> childNodes) {
        return Iterators.transform(
                childNodes,
                new Function<NodeDelegate, Node>() {
                    @Override
                    public Node apply(NodeDelegate nodeDelegate) {
                        return new NodeImpl<NodeDelegate>(nodeDelegate, sessionContext);
                    }
                });
    }

    private Iterator<Property> propertyIterator(Iterator<PropertyDelegate> properties) {
        return Iterators.transform(
                properties,
                new Function<PropertyDelegate, Property>() {
                    @Override
                    public Property apply(PropertyDelegate propertyDelegate) {
                        return new PropertyImpl(propertyDelegate, sessionContext);
                    }
                });
    }

    private void checkValidWorkspace(String workspaceName) throws RepositoryException {
        Workspace workspace = sessionContext.getWorkspace();
        for (String wn : workspace.getAccessibleWorkspaceNames()) {
            if (wn.equals(workspaceName)) {
                return;
            }
        }
        throw new NoSuchWorkspaceException(workspaceName + " does not exist.");
    }

    private void internalSetPrimaryType(final String nodeTypeName) throws RepositoryException {
        // TODO: figure out the right place for this check
        NodeType nt = getNodeTypeManager().getNodeType(nodeTypeName); // throws on not found
        if (nt.isAbstract() || nt.isMixin()) {
            throw new ConstraintViolationException();
        }
        // TODO: END

        PropertyState state = PropertyStates.createProperty(
                JCR_PRIMARYTYPE, getOakName(nodeTypeName), NAME);
        dlg.setProperty(state, true, true);
        dlg.setOrderableChildren(nt.hasOrderableChildNodes());
    }

    private Property internalSetProperty(
            String jcrName, final Value value, final boolean exactTypeMatch)
            throws RepositoryException {
        final String oakName = getOakPathOrThrow(checkNotNull(jcrName));
        final PropertyState state = createSingleState(
                oakName, value, Type.fromTag(value.getType(), false));
        return perform(new ItemWriteOperation<Property>() {
            @Override
            public void checkPreconditions() throws RepositoryException {
                super.checkPreconditions();
                if (!isCheckedOut()) {
                    throw new VersionException(
                            "Cannot set property. Node is checked in.");
                }
            }
            @Override
            public Property perform() throws RepositoryException {
                return new PropertyImpl(
                        dlg.setProperty(state, exactTypeMatch, false),
                        sessionContext);
            }
        });
    }

    private Property internalSetProperty(
            String jcrName, final Value[] values,
            final int type, final boolean exactTypeMatch)
            throws RepositoryException {
        final String oakName = getOakPathOrThrow(checkNotNull(jcrName));
        final PropertyState state = createMultiState(
                oakName, compact(values), Type.fromTag(type, true));
        return perform(new ItemWriteOperation<Property>() {
            @Override
            public void checkPreconditions() throws RepositoryException {
                super.checkPreconditions();
                if (!isCheckedOut()) {
                    throw new VersionException(
                            "Cannot set property. Node is checked in.");
                }
            }
            @Override
            public Property perform() throws RepositoryException {
                return new PropertyImpl(
                        dlg.setProperty(state, exactTypeMatch, false),
                        sessionContext);
            }
        });
    }

    /**
     * Removes all {@code null} values from the given array.
     *
     * @param values value array
     * @return value list without {@code null} entries
     */
    private static List<Value> compact(Value[] values) {
        List<Value> list = Lists.newArrayListWithCapacity(values.length);
        for (Value value : values) {
            if (value != null) {
                list.add(value);
            }
        }
        return list;
    }


    private Property internalRemoveProperty(final String jcrName)
            throws RepositoryException {
        final String oakName = getOakName(checkNotNull(jcrName));
        return perform(new ItemWriteOperation<Property>() {
            @Override
            public Property perform() throws RepositoryException {
                PropertyDelegate property = dlg.getPropertyOrNull(oakName);
                if (property != null) {
                    property.remove();
                } else {
                    // Return an instance which throws on access; see OAK-395
                    property = dlg.getProperty(oakName);
                }
                return new PropertyImpl(property, sessionContext);
            }
        });
    }

    //-----------------------------------------------------< JackrabbitNode >---
    @Override
    public void rename(String newName) throws RepositoryException {
        throw new UnsupportedRepositoryOperationException("TODO: JackrabbitNode.rename (OAK-770");
    }

    @Override
    public void setMixins(String[] strings) throws RepositoryException {
        throw new UnsupportedRepositoryOperationException("TODO: JackrabbitNode.setMixins (OAK-770");
    }
}
