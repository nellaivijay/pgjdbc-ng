package com.impossibl.postgres.jdbc;

import static com.impossibl.postgres.jdbc.SQLTextEscapeFunctions.concat;
import static com.impossibl.postgres.jdbc.SQLTextEscapeFunctions.getEscapeMethod;
import static com.impossibl.postgres.jdbc.SQLTextEscapeFunctions.grammar;
import static com.impossibl.postgres.jdbc.SQLTextEscapeFunctions.groupedSequence;
import static com.impossibl.postgres.jdbc.SQLTextEscapeFunctions.invokeEscape;
import static com.impossibl.postgres.jdbc.SQLTextEscapeFunctions.literal;
import static com.impossibl.postgres.jdbc.SQLTextEscapeFunctions.sequence;
import static com.impossibl.postgres.jdbc.SQLTextEscapeFunctions.space;

import java.lang.reflect.Method;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import com.impossibl.postgres.jdbc.SQLTextTree.CompositeNode;
import com.impossibl.postgres.jdbc.SQLTextTree.EscapeNode;
import com.impossibl.postgres.jdbc.SQLTextTree.Node;
import com.impossibl.postgres.jdbc.SQLTextTree.ParenGroupNode;
import com.impossibl.postgres.jdbc.SQLTextTree.PieceNode;
import com.impossibl.postgres.jdbc.SQLTextTree.Processor;
import com.impossibl.postgres.jdbc.SQLTextTree.StringLiteralPiece;
import com.impossibl.postgres.jdbc.SQLTextTree.UnquotedIdentifierPiece;
import com.impossibl.postgres.jdbc.SQLTextTree.WhitespacePiece;
import com.impossibl.postgres.system.Context;

public class SQLTextEscapes {
	
	static void processEscapes(SQLText text, final Context context) throws SQLException {

		text.process(new Processor() {

			@Override
			public Node process(Node node) throws SQLException {
				
				if(node instanceof EscapeNode == false) {
					return node;
				}

				return processEscape((EscapeNode) node, context);
			}
			
		}, true);

	}

	private static Node processEscape(EscapeNode escape, Context context) throws SQLException {
		
		UnquotedIdentifierPiece type = getNode(escape, 0, UnquotedIdentifierPiece.class);
		
		Node result = null;
		
		switch(type.toString().toLowerCase()) {
		case "fn":
			result = processFunctionEscape(escape);
			break;
			
		case "d":
			result = processDateEscape(escape, context);
			break;
			
		case "t":
			result = processTimeEscape(escape, context);
			break;
			
		case "ts":
			result = processTimestampEscape(escape, context);
			break;
			
		case "oj":
			result = processOuterJoinEscape(escape);
			break;
			
		case "call":
			result = processCallEscape(escape);
			break;
			
		case "?":
			result = processCallAssignEscape(escape);
			break;
			
		case "limit":
			result = processLimitEscape(escape);
			break;
			
		case "escape":
			result = processLikeEscape(escape);
			break;

		default:
			throw new SQLException("Invalid escape (" + escape.getStartPos() + ")", "Syntax Error");
		}
		
		return result;
	}
	
	private static Node processFunctionEscape(EscapeNode escape) throws SQLException {

		escape.removeAll(WhitespacePiece.class, false);

		checkSize(escape, 3);
		
		UnquotedIdentifierPiece name = getNode(escape, 1, UnquotedIdentifierPiece.class);
		List<Node> args = split(getNode(escape, 2, ParenGroupNode.class), false, ",");
		
		Method method = getEscapeMethod(name.toString());
		if(method == null) {
			throw new SQLException("Escape function not supported (" + escape.getStartPos() + "): " + name, "Syntax Error");
		}
		
		return invokeEscape(method, name.toString(), args);
	}

	private static Node processDateEscape(EscapeNode escape, Context context) throws SQLException {
		
		escape.removeAll(WhitespacePiece.class, false);

		checkSize(escape, 2);
		
		StringLiteralPiece dateLit = getNode(escape, 1, StringLiteralPiece.class);
		
		Date date;
		try {
			date = Date.valueOf(dateLit.getText());
		}
		catch(Exception e1) {
			throw new SQLException("invalid date format in escape (" + escape.getStartPos() + ")");
		}
		
		return sequence("DATE", space(), literal(date.toString()));
	}

	private static Node processTimeEscape(EscapeNode escape, Context context) throws SQLException {

		escape.removeAll(WhitespacePiece.class, false);

		checkSize(escape, 2);
		
		StringLiteralPiece timeLit = getNode(escape, 1, StringLiteralPiece.class);
		
		Time time;
		try {
			time = Time.valueOf(timeLit.toString());
		}
		catch(Exception e1) {
			throw new SQLException("invalid time format in escape (" + escape.getStartPos() + ")");
		}
		
		return sequence("TIME", space(), literal(time.toString()));
	}

	private static Node processTimestampEscape(EscapeNode escape, Context context) throws SQLException {
		
		escape.removeAll(WhitespacePiece.class, false);

		checkSize(escape, 2);
		
		StringLiteralPiece tsLit = getNode(escape, 1, StringLiteralPiece.class);
		
		Timestamp timestamp;
		try {
			timestamp = Timestamp.valueOf(tsLit.toString());
		}
		catch(Exception e) {
			throw new SQLException("invalid timestamp format in escape (" + escape.getStartPos() + ")");
		}
		
		return sequence("TIMESTAMP", space(), literal(timestamp.toString()));
	}

	private static Node processOuterJoinEscape(EscapeNode escape) throws SQLException {
		
		List<Node> nodes = split(escape, true, "OJ", "LEFT", "RIGHT", "FULL", "OUTER", "JOIN", "ON");
		
		checkSize(nodes, escape.getStartPos(), 8);
		checkLiteralNode(nodes.get(3), "OUTER");
		checkLiteralNode(nodes.get(4), "JOIN");
		checkLiteralNode(nodes.get(6), "ON");		

		return sequence(nodes.get(1), space(), nodes.get(2), "OUTER", "JOIN", nodes.get(5), "ON", nodes.get(7));
	}

	private static Node processCallAssignEscape(EscapeNode escape) throws SQLException {

		escape.removeAll(WhitespacePiece.class, false);
		
		checkSize(escape, 4, 5);
		
		checkLiteralNode(escape, 1, "=");
		checkLiteralNode(escape, 2, "call");
		
		return groupedSequence("SELECT", space(), grammar("*"), "FROM", getNode(escape, 1, UnquotedIdentifierPiece.class), space(), getNode(escape, 3, ParenGroupNode.class));
	}

	private static Node processCallEscape(EscapeNode escape) throws SQLException {

		escape.removeAll(WhitespacePiece.class, false);
		
		checkSize(escape, 2, 3);
		
		return groupedSequence("SELECT", space(), grammar("*"), "FROM", getNode(escape, 1, UnquotedIdentifierPiece.class), space(), getNode(escape, 2, ParenGroupNode.class));
	}

	private static Node processLimitEscape(EscapeNode escape) throws SQLException {

		escape.removeAll(WhitespacePiece.class, false);
		
		checkSize(escape, 2, 4);
		
		Node rows = getNode(escape, 1, Node.class);
		
		Node offset = null;
		if(escape.getNodeCount() == 4) {
			checkLiteralNode(escape, 2, "OFFSET");
			offset = getNode(escape, 3, Node.class);;
		}
		
		Node limit = sequence("LIMIT", rows);
		
		if(offset != null) {
			limit = concat(limit, sequence(space(), "OFFSET", offset));
		}

		return limit;
	}

	private static Node processLikeEscape(EscapeNode escape) throws SQLException {
		
		escape.removeAll(WhitespacePiece.class, false);
		
		checkSize(escape, 2);

		return sequence("ESCAPE", space(), getNode(escape, 1, Node.class));
	}

	private static void checkSize(CompositeNode comp, int... sizes) throws SQLException {

		checkSize(comp.nodes, comp.getStartPos(),  sizes);
	}
	
	private static void checkSize(List<Node> nodes, int startPos, int... sizes) throws SQLException {

		for(int size : sizes) {
			if(nodes.size() == size) {
				return;
			}
		}
		
		throw new SQLException("Invalid escape syntax (" + startPos + ")", "Syntax Error");
	}
	
	private static void checkLiteralNode(CompositeNode comp, int index, String text) throws SQLException {
		
		checkLiteralNode(getNode(comp, index, Node.class), text);		
	}
	
	private static void checkLiteralNode(Node test, String text) throws SQLException {
		
		if(test.toString().toUpperCase().equals(text.toUpperCase()) == false) {
			throw new SQLException("Invalid escape (" + test.getStartPos() + ")", "Syntax Error");
		}
		
	}
	
	private static <T extends Node> T getNode(CompositeNode comp, int idx, Class<T> nodeType) throws SQLException {
		
		Node node = comp.get(idx);
		if(nodeType.isInstance(node) == false)
			throw new SQLException("invalid escape (" + comp.getStartPos() + ")", "Syntax Error");
		
		return nodeType.cast(node);
	}

	private static List<Node> split(CompositeNode comp, boolean includeMatches, String... matches) {
	
		return split(comp.nodes, comp.getStartPos(), includeMatches, matches);
	}
	
	private static List<Node> split(List<Node> nodes, int startPos, boolean includeMatches, String... matches) {
		
		List<String> matchList = Arrays.asList(matches);
		
		if(nodes.size() == 0) {
			return Collections.emptyList();
		}
		
		CompositeNode current = new CompositeNode(startPos);

		List<Node> comps = new ArrayList<>();
		
		Iterator<Node> nodeIter = nodes.iterator();
		while(nodeIter.hasNext()) {
			
			Node node = nodeIter.next();
			
			if(node instanceof PieceNode && matchList.contains(((PieceNode) node).getText().toUpperCase())) {
				
				current.trim();
				
				if(current.getNodeCount() > 0) {
					
					comps.add(current);
					current = new CompositeNode(node.getEndPos());
					
				}
								
				if(includeMatches) {
					comps.add(node);
				}
				
			}
			else {
				
				current.add(node);
			}
			
		}
		
		current.trim();
		
		if(current.getNodeCount() > 0) {
			comps.add(current);
		}
		
		return comps;
	}

}
