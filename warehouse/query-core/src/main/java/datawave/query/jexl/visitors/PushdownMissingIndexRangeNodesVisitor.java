package datawave.query.jexl.visitors;

import datawave.query.config.IndexHole;
import datawave.query.config.ShardQueryConfiguration;
import datawave.query.jexl.JexlASTHelper;
import datawave.query.jexl.nodes.IndexHoleMarkerJexlNode;
import datawave.query.jexl.nodes.QueryPropertyMarker;
import datawave.query.parser.JavaRegexAnalyzer;
import datawave.query.exceptions.DatawaveFatalQueryException;
import datawave.query.jexl.LiteralRange;
import datawave.query.util.MetadataHelper;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.commons.jexl2.parser.ASTAndNode;
import org.apache.commons.jexl2.parser.ASTEQNode;
import org.apache.commons.jexl2.parser.ASTERNode;
import org.apache.commons.jexl2.parser.ASTReference;
import org.apache.commons.jexl2.parser.ASTReferenceExpression;
import org.apache.commons.jexl2.parser.JexlNode;
import org.apache.commons.jexl2.parser.JexlNodes;
import org.apache.commons.jexl2.parser.ParserTreeConstants;
import org.apache.log4j.Logger;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Visitor meant to 'push down' predicates for expressions that are not executable against the index because of missing data in the global index.
 */
public class PushdownMissingIndexRangeNodesVisitor extends RebuildingVisitor {
    
    private static final Logger log = Logger.getLogger(PushdownMissingIndexRangeNodesVisitor.class);
    
    // a metadata helper
    protected MetadataHelper helper;
    // the begin and end dates for the query
    protected String beginDate;
    protected String endDate;
    // datatype filter
    protected Set<String> dataTypeFilter;
    // the set of holes known to exist in the index
    protected SortedSet<IndexHole> indexHoles = new TreeSet<>();
    
    /**
     * Construct the visitor
     * 
     * @param config
     *            the logic configuration
     * @param helper
     *            the metadata helper
     */
    public PushdownMissingIndexRangeNodesVisitor(ShardQueryConfiguration config, MetadataHelper helper) {
        this.helper = helper;
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd");
        this.beginDate = format.format(config.getBeginDate());
        this.endDate = format.format(config.getEndDate());
        this.dataTypeFilter = config.getDatatypeFilter();
        this.indexHoles.addAll(config.getIndexHoles());
    }
    
    /**
     * helper method that constructs and applies the visitor.
     */
    public static <T extends JexlNode> T pushdownPredicates(T queryTree, ShardQueryConfiguration config, MetadataHelper helper) {
        PushdownMissingIndexRangeNodesVisitor visitor = new PushdownMissingIndexRangeNodesVisitor(config, helper);
        return (T) (queryTree.jjtAccept(visitor, null));
    }
    
    @Override
    public Object visit(ASTAndNode node, Object data) {
        List<JexlNode> leaves = new ArrayList<>();
        Map<LiteralRange<?>,List<JexlNode>> ranges = JexlASTHelper.getBoundedRanges(node, this.dataTypeFilter, this.helper, leaves, false);
        
        JexlNode andNode = JexlNodes.newInstanceOfType(node);
        andNode.image = node.image;
        andNode.jjtSetParent(node.jjtGetParent());
        
        // We have a bounded range completely inside of an AND/OR
        if (!ranges.isEmpty()) {
            andNode = delayIndexBoundedRange(ranges, leaves, node, andNode, data);
        } else {
            // We have no bounded range to replace, just proceed as normal
            JexlNodes.ensureCapacity(andNode, node.jjtGetNumChildren());
            for (int i = 0; i < node.jjtGetNumChildren(); i++) {
                JexlNode newChild = (JexlNode) node.jjtGetChild(i).jjtAccept(this, data);
                andNode.jjtAddChild(newChild, i);
                newChild.jjtSetParent(andNode);
            }
        }
        
        return andNode;
    }
    
    /**
     * Delay the ranges that overlap holes. The range map is expected to only be indexed ranges.
     */
    protected JexlNode delayIndexBoundedRange(Map<LiteralRange<?>,List<JexlNode>> ranges, List<JexlNode> leaves, ASTAndNode currentNode, JexlNode newNode,
                    Object data) {
        // Add all children in this AND/OR which are not a part of the range
        JexlNodes.ensureCapacity(newNode, leaves.size() + ranges.size());
        int index = 0;
        for (; index < leaves.size(); index++) {
            log.debug(leaves.get(index).image);
            // Add each child which is not a part of the bounded range, visiting them first
            JexlNode visitedChild = (JexlNode) leaves.get(index).jjtAccept(this, null);
            newNode.jjtAddChild(visitedChild, index);
            visitedChild.jjtSetParent(newNode);
        }
        
        for (Map.Entry<LiteralRange<?>,List<JexlNode>> range : ranges.entrySet()) {
            // If we have any terms that we expanded, wrap them in parens and add them to the parent
            ASTAndNode rangeNodes = new ASTAndNode(ParserTreeConstants.JJTANDNODE);
            
            JexlNodes.ensureCapacity(rangeNodes, range.getValue().size());
            for (int i = 0; i < range.getValue().size(); i++) {
                rangeNodes.jjtAddChild(range.getValue().get(i), i);
            }
            
            JexlNode child = rangeNodes;
            if (missingIndexRange(range.getKey())) {
                child = IndexHoleMarkerJexlNode.create(rangeNodes);
            } else {
                child = JexlNodes.wrap(rangeNodes);
            }
            
            newNode.jjtAddChild(child, index++);
            child.jjtSetParent(newNode);
        }
        
        // If we had no other nodes than this bounded range, we can strip out the original parent
        if (newNode.jjtGetNumChildren() == 1) {
            newNode.jjtGetChild(0).jjtSetParent(newNode.jjtGetParent());
            return newNode.jjtGetChild(0);
        }
        
        return newNode;
    }
    
    @Override
    public Object visit(ASTReferenceExpression node, Object data) {
        // if not already delayed somehow
        if (!QueryPropertyMarker.instanceOf(node, null)) {
            return super.visit(node, data);
        }
        return node;
    }
    
    @Override
    public Object visit(ASTReference node, Object data) {
        // if not already delayed somehow
        if (!QueryPropertyMarker.instanceOf(node, null)) {
            return super.visit(node, data);
        }
        return node;
    }
    
    @Override
    public Object visit(ASTEQNode node, Object data) {
        if (isIndexed(node) && missingIndexRange(node)) {
            return IndexHoleMarkerJexlNode.create(node);
        }
        return node;
    }
    
    @Override
    public Object visit(ASTERNode node, Object data) {
        if (isIndexed(node) && missingIndexRange(node)) {
            return IndexHoleMarkerJexlNode.create(node);
        }
        return node;
    }
    
    public boolean isIndexed(JexlNode node) {
        String field = JexlASTHelper.getIdentifier(node);
        try {
            return (field != null && this.helper.isIndexed(field, this.dataTypeFilter));
        } catch (TableNotFoundException e) {
            throw new IllegalStateException("Unable to find metadata table", e);
        }
    }
    
    private boolean missingIndexRange(ASTEQNode node) {
        Object literal = JexlASTHelper.getLiteralValue(node);
        if (literal != null) {
            String strLiteral = String.valueOf(literal);
            for (IndexHole hole : this.indexHoles) {
                if (hole.overlaps(this.beginDate, this.endDate, strLiteral)) {
                    return true;
                } else if (hole.after(strLiteral)) {
                    return false;
                }
            }
        }
        return false;
    }
    
    private boolean missingIndexRange(ASTERNode node) {
        Object literal = JexlASTHelper.getLiteralValue(node);
        if (literal != null) {
            String strLiteral = String.valueOf(literal);
            JavaRegexAnalyzer analyzer = null;
            try {
                analyzer = new JavaRegexAnalyzer(strLiteral);
                if (analyzer.isLeadingLiteral()) {
                    String leadingLiteral = analyzer.getLeadingLiteral();
                    StringBuilder endRange = new StringBuilder().append(leadingLiteral);
                    char lastChar = leadingLiteral.charAt(leadingLiteral.length() - 1);
                    if (lastChar < Character.MAX_VALUE) {
                        lastChar++;
                        endRange.setCharAt(endRange.length() - 1, lastChar);
                    } else {
                        endRange.append((char) 0);
                    }
                    
                    for (IndexHole hole : indexHoles) {
                        if (hole.overlaps(this.beginDate, this.endDate, leadingLiteral, endRange.toString())) {
                            return true;
                        } else if (hole.after(strLiteral)) {
                            return false;
                        }
                    }
                }
            } catch (JavaRegexAnalyzer.JavaRegexParseException e) {
                log.error("Unable to parse regex " + strLiteral, e);
                throw new DatawaveFatalQueryException("Unable to parse regex " + strLiteral, e);
            }
        }
        return false;
    }
    
    private boolean missingIndexRange(LiteralRange range) {
        String strUpper = String.valueOf(range.getUpper());
        String strLower = String.valueOf(range.getLower());
        for (IndexHole hole : indexHoles) {
            if (hole.overlaps(this.beginDate, this.endDate, strLower, strUpper)) {
                return true;
            } else if (hole.after(strLower)) {
                return false;
            }
        }
        return false;
    }
    
}
