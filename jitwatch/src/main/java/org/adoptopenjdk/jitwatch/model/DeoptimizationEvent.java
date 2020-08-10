package org.adoptopenjdk.jitwatch.model;

import java.util.List;
import java.util.Map;

import static org.adoptopenjdk.jitwatch.core.JITWatchConstants.*;

public class DeoptimizationEvent
{
    private final long stamp;
    private final String compileID;
    private final Map<String, String> tagAttributes;
    /**
     * From the deoptimized method to the method that caused deoptimization
     */
    private final List<IMetaMember> deoptimizationChain;
    public final String tag;

    public DeoptimizationEvent(long stamp, String compileID, Map<String, String> tagAttributes, List<IMetaMember> deoptimizationChain, String tag)
    {
        this.stamp = stamp;
        this.compileID = compileID;
        this.tagAttributes = tagAttributes;
        this.deoptimizationChain = deoptimizationChain;
        this.tag = tag;
    }

    public IMetaMember getDeoptimizedMethod()
    {
        assert !deoptimizationChain.isEmpty();
        return deoptimizationChain.get(0);
    }

    public long getStamp()
    {
        return stamp;
    }

    public String getCompileID()
    {
        return compileID;
    }

    public String getReason()
    {
        return tagAttributes.get(ATTR_REASON);
    }

    public String getAction()
    {
        return tagAttributes.get(ATTR_ACTION);
    }

    public String getComment()
    {
        return tagAttributes.get(ATTR_COMMENT);
    }

    public List<IMetaMember> getDeoptimizationChain() {
        return deoptimizationChain;
    }
}
