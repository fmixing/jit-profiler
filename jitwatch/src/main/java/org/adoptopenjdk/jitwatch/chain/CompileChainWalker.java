/*
 * Copyright (c) 2013-2016 Chris Newland.
 * Licensed under https://github.com/AdoptOpenJDK/jitwatch/blob/master/LICENSE-BSD
 * Instructions: https://github.com/AdoptOpenJDK/jitwatch/wiki
 */
package org.adoptopenjdk.jitwatch.chain;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.adoptopenjdk.jitwatch.compilation.AbstractCompilationVisitable;
import org.adoptopenjdk.jitwatch.compilation.CompilationUtil;
import org.adoptopenjdk.jitwatch.model.Compilation;
import org.adoptopenjdk.jitwatch.model.IParseDictionary;
import org.adoptopenjdk.jitwatch.model.IReadOnlyJITDataModel;
import org.adoptopenjdk.jitwatch.model.LogParseException;
import org.adoptopenjdk.jitwatch.model.Tag;
import org.adoptopenjdk.jitwatch.util.TooltipUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.adoptopenjdk.jitwatch.core.JITWatchConstants.*;

public class CompileChainWalker extends AbstractCompilationVisitable
{
	private static final Logger logger = LoggerFactory.getLogger(CompileChainWalker.class);

	private IReadOnlyJITDataModel model;

	private CompileNode root = null;
	
	private Compilation compilation;

	public CompileChainWalker(IReadOnlyJITDataModel model)
	{
		this.model = model;
		
		ignoreTags.add(TAG_DIRECT_CALL);
		ignoreTags.add(TAG_KLASS);
		ignoreTags.add(TAG_TYPE);
		ignoreTags.add(TAG_DEPENDENCY);	
		ignoreTags.add(TAG_PREDICTED_CALL);
		ignoreTags.add(TAG_PARSE_DONE);
		ignoreTags.add(TAG_PHASE_DONE);
		ignoreTags.add(TAG_BRANCH);
		ignoreTags.add(TAG_UNCOMMON_TRAP);
		ignoreTags.add(TAG_INTRINSIC);
		ignoreTags.add(TAG_OBSERVE);
		ignoreTags.add(TAG_HOT_THROW);
		ignoreTags.add(TAG_CAST_UP);
		ignoreTags.add(TAG_HOT_THROW);
	}

	public CompileNode buildCallTree(Compilation compilation)
	{
		this.root = null;
		
		this.compilation = compilation;

		try
		{
			CompilationUtil.visitParseTagsOfCompilation(compilation, this);
		}
		catch (LogParseException lpe)
		{
			logger.error("Could not build compile tree", lpe);
		}

		return root;
	}

	private void processParseTag(Tag parseTag, CompileNode parentNode, IParseDictionary parseDictionary)
	{
		String methodID = null;
		CompileNode lastNode = null;

		Map<String, Map<String, String>> methodIDToNameAndHolder = new HashMap<>();
		String methodAttrsMethodID = null;
		Map<String, String> methodAttrs = new HashMap<>();
		String callAttrsMethodID = null;
		Map<String, String> callAttrs = new HashMap<>();

		for (Tag child : parseTag.getChildren())
		{
			String tagName = child.getName();
			Map<String, String> tagAttrs = child.getAttributes();

			switch (tagName)
			{
				case TAG_BC:
				{
					callAttrs.clear();
					callAttrsMethodID = null;
					break;
				}

				case TAG_METHOD:
				{
					methodID = tagAttrs.get(ATTR_ID);
					methodIDToNameAndHolder.put(methodID, Map.of(ATTR_HOLDER, tagAttrs.get(ATTR_HOLDER), ATTR_NAME, tagAttrs.get(ATTR_NAME)));
					methodAttrs.clear();
					methodAttrs.putAll(tagAttrs);
					methodAttrsMethodID = methodID;
					break;
				}

				case TAG_CALL:
				{
					methodID = tagAttrs.get(ATTR_METHOD);
					callAttrs.clear();
					callAttrs.putAll(tagAttrs);
					callAttrsMethodID = methodID;
					break;
				}

				case TAG_INLINE_FAIL:
				{
					Map<String, String> correctMethodAttrs = getAttrsCorrectly(methodID, methodAttrsMethodID, methodAttrs,
							methodIDToNameAndHolder.get(methodID));
					Map<String, String> correctCallAttrs = getAttrsCorrectly(methodID, callAttrsMethodID, callAttrs);
					createChildNode(parentNode, methodID, parseDictionary, false, false, correctMethodAttrs,
							correctCallAttrs, tagAttrs);
					methodID = null;
					lastNode = null;
					methodAttrsMethodID = null;
					break;
				}

				case TAG_INLINE_SUCCESS:
				{
					Map<String, String> correctMethodAttrs = getAttrsCorrectly(methodID, methodAttrsMethodID, methodAttrs,
							methodIDToNameAndHolder.get(methodID));
					Map<String, String> correctCallAttrs = getAttrsCorrectly(methodID, callAttrsMethodID, callAttrs);
					lastNode = createChildNode(parentNode, methodID, parseDictionary, true, false,
							correctMethodAttrs, correctCallAttrs, tagAttrs);
					break;
				}

				case TAG_PARSE: // call depth
				{
					String childMethodID = tagAttrs.get(ATTR_METHOD);

					CompileNode nextParent = parentNode;

					if (lastNode != null)
					{
						nextParent = lastNode;
					}
					else if (child.getNamedChildren(TAG_PARSE).size() > 0)
					{
						CompileNode childNode = new CompileNode(childMethodID);

						parentNode.addChild(childNode);

						nextParent = childNode;
					}

					processParseTag(child, nextParent, parseDictionary);

					break;
				}

				case TAG_PHASE:
				{
					String phaseName = tagAttrs.get(ATTR_NAME);

					if (S_PARSE_HIR.equals(phaseName))
					{
						processParseTag(child, parentNode, parseDictionary);
					}
					else
					{
						logger.warn("Don't know how to handle phase {}", phaseName);
					}
					break;
				}

				case TAG_VIRTUAL_CALL:
					Map<String, String> correctMethodAttrs = getAttrsCorrectly(methodID, methodAttrsMethodID, methodAttrs,
							methodIDToNameAndHolder.get(methodID));
					Map<String, String> correctCallAttrs = getAttrsCorrectly(methodID, callAttrsMethodID, callAttrs);
					lastNode = createChildNode(parentNode, methodID, parseDictionary, false, true,
							correctMethodAttrs, correctCallAttrs, tagAttrs);
					break;

				default:
					handleOther(child);
					break;
			}
		}
	}

	private Map<String, String> getAttrsCorrectly(String currentMethodID, String attrsMethodID,
														Map<String, String> attrs) {
		return getAttrsCorrectly(currentMethodID, attrsMethodID, attrs, Collections.emptyMap());
	}

	/**
	 * We need to make sure that we use the right attrs when building node information. For that reason, we need to check
	 * if the methodID for attrs did not change.
	 * @param currentMethodID methodID for the method we process right now
	 * @param attrsMethodID known methodID when attrs were filled
	 */
	private Map<String, String> getAttrsCorrectly(String currentMethodID, String attrsMethodID, Map<String, String> attrs,
												  Map<String, String> orElse) {
		if (!Objects.equals(currentMethodID, attrsMethodID)) {
			return (orElse != null) ? orElse : Collections.emptyMap();
		}
		return attrs;
	}

	private CompileNode createChildNode(CompileNode parentNode, String methodID, IParseDictionary parseDictionary, boolean inlined, boolean virtualCall,
			Map<String, String> methodAttrs, Map<String, String> callAttrs, Map<String, String> tagAttrs)
	{
		CompileNode childNode = new CompileNode(methodID);
		parentNode.addChild(childNode);

		String reason = tagAttrs.get(ATTR_REASON);
		String tooltip = TooltipUtil.buildInlineAnnotationText(inlined, reason, callAttrs, methodAttrs, parseDictionary);
		
		childNode.setInlined(inlined);
		childNode.setVirtualCall(virtualCall);
		childNode.setTooltipText(tooltip);
		
		return childNode;
	}

	@Override
	public void visitTag(Tag parseTag, IParseDictionary parseDictionary) throws LogParseException
	{
		String methodID = parseTag.getAttributes().get(ATTR_METHOD);

		// only initialise on first parse tag.
		// there may be multiple if late_inline
		// is detected
		if (root == null)
		{
			root = CompileNode.createRootNode(compilation, methodID, parseDictionary, model);
		}

		processParseTag(parseTag, root, parseDictionary);
	}
}
