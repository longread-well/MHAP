package com.secret.fastalign.general;

import java.util.concurrent.atomic.AtomicInteger;

public final class SequenceId
{
	//private static ConcurrentHashMap<Long, String> indicies = new ConcurrentHashMap<Long, String>();
	private static AtomicInteger globalCounter = new AtomicInteger(0);
	
	private final int id;
	private final boolean isFwd;
	
	public SequenceId(String id)
	{
		this.id = globalCounter.addAndGet(1);
		//indicies.put(this.id, id);
		
		this.isFwd = true;
	}
	
	private SequenceId(int id, boolean isFwd)
	{
		this.id = id;
		this.isFwd = isFwd;
	}
	
	public SequenceId complimentId()
	{
		return new SequenceId(this.id, !this.isFwd);
	}
	
	/* (non-Javadoc)
	 * @see java.lang.Object#equals(java.lang.Object)
	 */
	@Override
	public boolean equals(Object obj)
	{
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		SequenceId other = (SequenceId) obj;
		
		return (this.id==other.id) && (this.isFwd == other.isFwd);
	}
	
	public boolean isForward()
	{
		return this.isFwd;
	}
	
	public long getHeaderId()
	{
		return this.id;
	}

	public String getHeader()
	{
		//String s = indicies.get(this.id);
		//if (s!=null)
		//	return s;
		
		return String.valueOf(this.id);
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode()
	{
		return this.isFwd? this.id : -this.id;
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString()
	{
		return ""+getHeader()+(this.isFwd ? "(fwd)" : "(rev)");
	}
	
	public String toStringInt()
	{
		return ""+getHeader()+(this.isFwd ? " 1" : " 0");
	}
}
