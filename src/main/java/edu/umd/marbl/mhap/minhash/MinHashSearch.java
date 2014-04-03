/* 
 * MHAP package
 * 
 * This  software is distributed "as is", without any warranty, including 
 * any implied warranty of merchantability or fitness for a particular
 * use. The authors assume no responsibility for, and shall not be liable
 * for, any special, indirect, or consequential damages, or any damages
 * whatsoever, arising out of or in connection with the use of this
 * software.
 * 
 * Copyright (c) 2014 by Konstantin Berlin and Sergey Koren
 * University Of Maryland
 * 
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
 * 
 */
package edu.umd.marbl.mhap.minhash;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicLong;

import edu.umd.marbl.mhap.general.AbstractMatchSearch;
import edu.umd.marbl.mhap.general.AbstractSequenceHashStreamer;
import edu.umd.marbl.mhap.general.MatchResult;
import edu.umd.marbl.mhap.general.OverlapInfo;
import edu.umd.marbl.mhap.general.SequenceId;
import edu.umd.marbl.mhap.utils.FastAlignRuntimeException;
import edu.umd.marbl.mhap.utils.Utils;

public final class MinHashSearch extends AbstractMatchSearch<SequenceMinHashes>
{
	public static final class HitInfo
	{
		public int count;

		public HitInfo()
		{
			this.count = 0;
		}

		public void addHit()
		{
			this.count++;
		}
	}

	private final double acceptScore;

	private final ArrayList<HashMap<Integer, ArrayList<SequenceId>>> hashes;
	private final double maxShift;
	private final int minStoreLength;
	private final AtomicLong numberElementsProcessed;
	private final AtomicLong numberSequencesFullyCompared;

	private final AtomicLong numberSequencesHit;
	private final AtomicLong numberSequencesMinHashed;
	private final AtomicLong numberSubSequences;
	private final AtomicLong numberSubSequencesHit;

	private final int numMinMatches;
	private final HashMap<SequenceId, SequenceMinHashes> sequenceVectorsHash;

	
	public MinHashSearch(AbstractSequenceHashStreamer<SequenceMinHashes> data, int numHashes, int numMinMatches, int numThreads, 
			boolean storeResults, int minStoreLength, double maxShift, double acceptScore) throws IOException
	{
		super(numThreads, storeResults);

		this.minStoreLength = minStoreLength;
		this.numMinMatches = numMinMatches;
		this.maxShift = maxShift;
		this.acceptScore = acceptScore;
		this.numberSubSequencesHit = new AtomicLong();
		this.numberSequencesHit = new AtomicLong();
		this.numberSequencesFullyCompared = new AtomicLong();
		this.numberSubSequences = new AtomicLong();
		this.numberSequencesMinHashed = new AtomicLong();
		this.numberElementsProcessed = new AtomicLong();
		
		// enqueue full file, since have to know full size
		data.enqueueFullFile(false, this.numThreads);

		this.sequenceVectorsHash = new HashMap<SequenceId, SequenceMinHashes>(data.getNumberProcessed() + 100, (float) 0.75);

		this.hashes = new ArrayList<HashMap<Integer, ArrayList<SequenceId>>>(numHashes);
		for (int iter = 0; iter < numHashes; iter++)
			this.hashes.add(new HashMap<Integer, ArrayList<SequenceId>>(data.getNumberSubSequencesProcessed()+100));
		
		addData(data);
	}

	@Override
	public boolean addSequence(SequenceMinHashes currHash)
	{
		int[] currMinHashes = currHash.getMinHashes().getMinHashArray();

		if (currMinHashes.length != this.hashes.size())
			throw new FastAlignRuntimeException("Number of hashes does not match.");

		// put the result into the hashmap
		synchronized (this.sequenceVectorsHash)
		{
			SequenceMinHashes minHash = this.sequenceVectorsHash.put(currHash.getSequenceId(), currHash);
			if (minHash != null)
			{
				this.sequenceVectorsHash.put(currHash.getSequenceId(), minHash);

				throw new FastAlignRuntimeException("Sequence id already exists in the hashtable.");
			}			
		}
		
		// add the hashes
		int count = 0;
		SequenceId id = currHash.getSequenceId();
		for (HashMap<Integer, ArrayList<SequenceId>> hash : this.hashes)
		{
			ArrayList<SequenceId> currList;
			final int hashVal = currMinHashes[count];

			// get the list
			synchronized (hash)
			{
				currList = hash.get(hashVal);

				if (currList == null)
				{
					currList = new ArrayList<SequenceId>(2);
					hash.put(hashVal, currList);
				}
			}

			// add the element
			synchronized (currList)
			{
				currList.add(id);
			}
			
			count++;
		}

		//increment the subsequence counter 
		this.numberSubSequences.getAndIncrement();
		
		//increment the counter
		this.numberSequencesMinHashed.getAndIncrement();
		
		return true;
	}

	@Override
	public List<MatchResult> findMatches(SequenceMinHashes seqHashes, boolean toSelf)
	{
		MinHash minHash = seqHashes.getMinHashes();

		if (this.hashes.size() != minHash.numHashes())
			throw new FastAlignRuntimeException("Number of hashes does not match. Stored size " + this.hashes.size()
					+ ", input size " + minHash.numHashes() + ".");

		HashMap<SequenceId, HitInfo> bestSequenceHit = new HashMap<SequenceId, HitInfo>(this.numberSequencesMinHashed.intValue()/5+1);
		int[] minHashes = minHash.getMinHashArray();
		
		int hashIndex = 0;
		for (HashMap<Integer,ArrayList<SequenceId>> currHash : this.hashes)
		{
			ArrayList<SequenceId> currentHashMatchList = currHash.get(minHashes[hashIndex]);

			// if some matches exist add them
			if (currentHashMatchList != null)
			{
				this.numberElementsProcessed.getAndAdd(currentHashMatchList.size());

				for (SequenceId sequenceId : currentHashMatchList)
				{
					// get current count in the list
					HitInfo currentHitInfo = bestSequenceHit.get(sequenceId);

					// increment the count
					if (currentHitInfo == null)
					{
						currentHitInfo = new HitInfo();
						bestSequenceHit.put(sequenceId, currentHitInfo);
					}

					// record the match of the kmer hash
					currentHitInfo.addHit();
				}
			}
			
			hashIndex++;
		}
		
		//record number of hash matches processed
		this.numberSequencesHit.getAndAdd(bestSequenceHit.size());
		this.numberSubSequencesHit.getAndAdd(bestSequenceHit.size());
		
		// compute the proper counts for all sets and remove below threshold
		ArrayList<MatchResult> matches = new ArrayList<MatchResult>(32);
		
		for (Entry<SequenceId, HitInfo> match : bestSequenceHit.entrySet())
		{
			//get the match id
			SequenceId matchId = match.getKey();
			
			// do not store matches with smaller ids, unless its coming from a short read
			if (toSelf && matchId.getHeaderId() == seqHashes.getSequenceId().getHeaderId())
				continue;

			//see if the hit number is high enough			
			if (match.getValue().count >= this.numMinMatches)
			{
				SequenceMinHashes matchedHashes = this.sequenceVectorsHash.get(match.getKey());
				if (matchedHashes==null)
					throw new FastAlignRuntimeException("Hashes not found for given id.");
				
				//never process short to short
				if (matchedHashes.getSequenceLength()<this.minStoreLength && seqHashes.getSequenceLength()<this.minStoreLength)
					continue;
				
				//never process long to long in self, with greater id
				if (toSelf 
						&& matchId.getHeaderId() > seqHashes.getSequenceId().getHeaderId()
						&& matchedHashes.getSequenceLength()>=this.minStoreLength
						&& seqHashes.getSequenceLength()>=this.minStoreLength)
					continue;
				
				//never do short to long
				if (toSelf 
						&& matchedHashes.getSequenceLength()<this.minStoreLength
						&& seqHashes.getSequenceLength()>=this.minStoreLength)
					continue;
				
				OverlapInfo result = seqHashes.getOrderedHashes().getFullScore(matchedHashes.getOrderedHashes(), this.maxShift);
				
				//OverlapInfo result2 = seqMinHashes.getOrderedHashes().getFullScoreExperimental(matchedHashes.getOrderedHashes(), this.maxShift);				
				//System.err.println(result+"  "+result2);
				
				//increment the counter
				this.numberSequencesFullyCompared.getAndIncrement();
				
				//if score is good add
				if (result.score >= this.acceptScore)
				{
					MatchResult currResult = new MatchResult(seqHashes.getSequenceId(), matchId, result, seqHashes.getSequenceLength(), matchedHashes.getSequenceLength());

					// add to list
					matches.add(currResult);
				}
			}
		}

		return matches;
	}

	public long getNumberElementsProcessed()
	{
		return this.numberElementsProcessed.get();
	}

	public long getNumberSequenceHashed()
	{
		return this.numberSequencesMinHashed.get();
	}

	public long getNumberSequencesFullyCompared()
	{
		return this.numberSequencesFullyCompared.get();
	}

	public long getNumberSequencesHit()
	{
		return this.numberSequencesHit.get();
	}
	
	public long getNumberSubSequencesHit()
	{
		return this.numberSubSequencesHit.get();
	}
	
	public double hashTableNormalizedEnthropy()
	{
		double sum = 0.0;
		for (HashMap<Integer, ArrayList<SequenceId>> map : this.hashes)
		{
			sum+=Utils.hashEfficiency(map);
		}
		
		return sum/(double)this.hashes.size();
	}
	
	@Override
	public List<SequenceId> getStoredForwardSequenceIds()
	{
		ArrayList<SequenceId> seqIds = new ArrayList<SequenceId>(this.sequenceVectorsHash.size());
		for (SequenceMinHashes hashes : this.sequenceVectorsHash.values())
			if (hashes.getSequenceId().isForward())
				seqIds.add(hashes.getSequenceId());
		
		return seqIds;
	}

	@Override
	public SequenceMinHashes getStoredSequenceHash(SequenceId id)
	{
		return this.sequenceVectorsHash.get(id);
	}

	@Override
	public int size()
	{
		return this.sequenceVectorsHash.size();
	}
}