"""Seed the demo graph with a realistic AI industry research scenario.

Populates the shared Neo4j graph via the bridge server with entities,
facts, conversations, and reasoning traces from all four agents — showing
how Python, TypeScript, and Go agents collaborate on the same graph.

Usage:
    uv run python demo/seed-data.py

Requires the bridge server at localhost:3001 connected to Neo4j.
"""

import asyncio
import httpx

BRIDGE = "http://localhost:3001"


async def call(client: httpx.AsyncClient, method: str, params: dict) -> dict:
    resp = await client.post(f"{BRIDGE}/{method}", json=params, timeout=30)
    if resp.status_code == 204:
        return {}
    return resp.json()


async def seed():
    async with httpx.AsyncClient() as c:
        print("Clearing existing data...")
        await call(c, "clear_all_data", {})

        # ==================================================================
        # ENTITIES — Created by Lenny (Python/PydanticAI podcast research)
        # ==================================================================
        print("\n--- Lenny (Python/PydanticAI) creating entities ---")

        people = {}
        for name, desc in [
            ("Sam Altman", "CEO of OpenAI, leading the development of GPT models"),
            ("Dario Amodei", "CEO of Anthropic, former VP of Research at OpenAI"),
            ("Jensen Huang", "CEO and founder of NVIDIA, pioneer of GPU computing"),
            ("Demis Hassabis", "CEO of Google DeepMind, Nobel Prize winner in Chemistry"),
            ("Satya Nadella", "CEO of Microsoft, architect of the OpenAI partnership"),
            ("Lex Fridman", "MIT researcher and podcast host, interviewed 400+ guests"),
            ("Ilya Sutskever", "Co-founder of Safe Superintelligence Inc, former OpenAI chief scientist"),
            ("Yann LeCun", "Chief AI Scientist at Meta, Turing Award winner"),
        ]:
            e = await call(c, "add_entity", {"name": name, "entity_type": "PERSON", "description": desc})
            people[name] = e["id"]
            print(f"  + {name} (PERSON)")

        orgs = {}
        for name, desc in [
            ("OpenAI", "AI research lab, creator of GPT-4 and ChatGPT"),
            ("Anthropic", "AI safety company, creator of Claude"),
            ("NVIDIA", "Leading GPU manufacturer powering AI infrastructure"),
            ("Google DeepMind", "AI research lab, creator of AlphaFold and Gemini"),
            ("Microsoft", "Technology giant, $13B investor in OpenAI"),
            ("Meta AI", "AI research division of Meta, developing LLaMA models"),
            ("Safe Superintelligence Inc", "AI safety startup founded by Ilya Sutskever"),
        ]:
            e = await call(c, "add_entity", {"name": name, "entity_type": "ORGANIZATION", "description": desc})
            orgs[name] = e["id"]
            print(f"  + {name} (ORGANIZATION)")

        events = {}
        for name, desc in [
            ("Lex Fridman Podcast #401", "Interview with Sam Altman about AGI and OpenAI's mission"),
            ("Lex Fridman Podcast #380", "Interview with Dario Amodei on AI safety and Anthropic"),
            ("NeurIPS 2025", "Conference on neural information processing systems"),
        ]:
            e = await call(c, "add_entity", {"name": name, "entity_type": "EVENT", "description": desc})
            events[name] = e["id"]
            print(f"  + {name} (EVENT)")

        # ==================================================================
        # FACTS — Relationships between entities
        # ==================================================================
        print("\n--- Recording facts across all agents ---")

        facts = [
            # Leadership
            ("Sam Altman", "CEO_OF", "OpenAI"),
            ("Dario Amodei", "CEO_OF", "Anthropic"),
            ("Jensen Huang", "CEO_OF", "NVIDIA"),
            ("Demis Hassabis", "CEO_OF", "Google DeepMind"),
            ("Satya Nadella", "CEO_OF", "Microsoft"),
            ("Yann LeCun", "CHIEF_SCIENTIST_AT", "Meta AI"),
            ("Ilya Sutskever", "FOUNDED", "Safe Superintelligence Inc"),
            # Investments & Partnerships
            ("Microsoft", "INVESTED_$13B_IN", "OpenAI"),
            ("NVIDIA", "SUPPLIES_GPUS_TO", "OpenAI"),
            ("NVIDIA", "SUPPLIES_GPUS_TO", "Anthropic"),
            ("NVIDIA", "SUPPLIES_GPUS_TO", "Google DeepMind"),
            # Products
            ("OpenAI", "CREATED", "GPT-4"),
            ("Anthropic", "CREATED", "Claude"),
            ("Google DeepMind", "CREATED", "Gemini"),
            ("Meta AI", "CREATED", "LLaMA"),
            ("NVIDIA", "MANUFACTURES", "H100 GPU"),
            # Career Moves
            ("Dario Amodei", "PREVIOUSLY_AT", "OpenAI"),
            ("Ilya Sutskever", "PREVIOUSLY_AT", "OpenAI"),
            # Interviews
            ("Lex Fridman", "INTERVIEWED", "Sam Altman"),
            ("Lex Fridman", "INTERVIEWED", "Dario Amodei"),
            ("Lex Fridman", "INTERVIEWED", "Jensen Huang"),
            ("Lex Fridman", "INTERVIEWED", "Demis Hassabis"),
            ("Lex Fridman", "INTERVIEWED", "Yann LeCun"),
            # Events
            ("Sam Altman", "SPOKE_AT", "NeurIPS 2025"),
            ("Demis Hassabis", "SPOKE_AT", "NeurIPS 2025"),
        ]

        for subj, pred, obj in facts:
            await call(c, "add_fact", {"subject": subj, "predicate": pred, "obj": obj})
            print(f"  + {subj} --[{pred}]--> {obj}")

        # ==================================================================
        # LENNY CONVERSATION — Podcast research session
        # ==================================================================
        print("\n--- Lenny (Python/PydanticAI) conversation ---")
        sid = "lenny-podcast-001"
        for role, content in [
            ("user", "Analyze the Lex Fridman podcast episode #401 with Sam Altman. Extract all people, companies, and key topics discussed."),
            ("assistant", "I'll analyze the transcript and extract entities. I found several key people and organizations mentioned in the episode."),
            ("user", "Great. What are the key relationships between these entities?"),
            ("assistant", "Sam Altman is the CEO of OpenAI. The episode discusses the $13B Microsoft investment, NVIDIA's role as GPU supplier, and Dario Amodei's departure from OpenAI to found Anthropic. Lex Fridman also references his previous interview with Dario (#380)."),
            ("user", "Can you also capture the main topics discussed?"),
            ("assistant", "The main topics were: AGI timeline predictions, AI safety concerns, the competitive landscape between OpenAI and Anthropic, NVIDIA's GPU dominance, and the role of open-source models like Meta's LLaMA."),
        ]:
            await call(c, "add_message", {"session_id": sid, "role": role, "content": content})
        print(f"  + 6 messages in {sid}")

        # Lenny reasoning trace
        trace = await call(c, "start_trace", {"session_id": sid, "task": "Extract entities from Lex Fridman podcast #401 with Sam Altman"})
        s1 = await call(c, "add_step", {"trace_id": trace["id"], "thought": "Parsing transcript for named entities", "action": "parse_transcript", "observation": "Found 8 people, 7 organizations, 3 events"})
        await call(c, "record_tool_call", {"step_id": s1["id"], "tool_name": "entity_extractor", "arguments": {"source": "podcast_401_transcript.txt"}, "status": "success", "duration_ms": 2340, "result": {"people": 8, "orgs": 7, "events": 3}})
        s2 = await call(c, "add_step", {"trace_id": trace["id"], "thought": "Identifying relationships between entities", "action": "relationship_extraction", "observation": "Found 25 facts connecting entities"})
        await call(c, "record_tool_call", {"step_id": s2["id"], "tool_name": "relationship_extractor", "arguments": {"entity_count": 18}, "status": "success", "duration_ms": 1870, "result": {"facts": 25}})
        s3 = await call(c, "add_step", {"trace_id": trace["id"], "thought": "Storing entities and facts in knowledge graph", "action": "store_knowledge", "observation": "All entities and facts stored successfully"})
        await call(c, "record_tool_call", {"step_id": s3["id"], "tool_name": "memory_store", "arguments": {"entities": 18, "facts": 25}, "status": "success", "duration_ms": 450})
        await call(c, "complete_trace", {"trace_id": trace["id"], "outcome": "Extracted 18 entities and 25 facts from podcast #401", "success": True})
        print(f"  + Reasoning trace: {trace['id'][:8]}... (3 steps, 3 tool calls)")

        # ==================================================================
        # SCOUT CONVERSATION — Web search enrichment
        # ==================================================================
        print("\n--- Scout (TypeScript/Vercel AI SDK) conversation ---")
        sid = "scout-search-001"
        for role, content in [
            ("user", "Search for the latest news about OpenAI and their recent developments."),
            ("assistant", "I found several recent developments. OpenAI launched GPT-4 Turbo with improved performance and lower costs. They also announced a partnership with Apple for AI integration in iOS."),
            ("user", "What about Anthropic? Any recent updates?"),
            ("assistant", "Anthropic released Claude 3.5 Sonnet, which shows strong performance on coding and reasoning benchmarks. They also raised $2B in a Series D round led by Google, bringing total funding to $7.3B."),
            ("user", "How does the competitive landscape look between these companies?"),
            ("assistant", "The AI landscape is increasingly competitive. OpenAI leads in consumer adoption with ChatGPT. Anthropic focuses on safety and enterprise. Google DeepMind is integrating Gemini across Google products. Meta is pushing open-source with LLaMA. NVIDIA remains the critical infrastructure provider for all of them."),
        ]:
            await call(c, "add_message", {"session_id": sid, "role": role, "content": content})
        print(f"  + 6 messages in {sid}")

        # Scout reasoning trace
        trace = await call(c, "start_trace", {"session_id": sid, "task": "Web search: latest AI industry developments"})
        s1 = await call(c, "add_step", {"trace_id": trace["id"], "thought": "Checking existing entities in shared graph", "action": "search_entities", "observation": "Found 18 entities from Lenny's research"})
        await call(c, "record_tool_call", {"step_id": s1["id"], "tool_name": "search_entities", "arguments": {"query": "OpenAI Anthropic", "limit": 10}, "status": "success", "duration_ms": 120})
        s2 = await call(c, "add_step", {"trace_id": trace["id"], "thought": "Searching web for latest OpenAI news", "action": "web_search", "observation": "Found 5 relevant articles about GPT-4 Turbo and Apple partnership"})
        await call(c, "record_tool_call", {"step_id": s2["id"], "tool_name": "web_search", "arguments": {"query": "OpenAI latest news 2025"}, "status": "success", "duration_ms": 890})
        s3 = await call(c, "add_step", {"trace_id": trace["id"], "thought": "Enriching entities with new information", "action": "enrich_entities", "observation": "Updated 3 entities with latest data"})
        await call(c, "record_tool_call", {"step_id": s3["id"], "tool_name": "add_fact", "arguments": {"subject": "OpenAI", "predicate": "LAUNCHED", "obj": "GPT-4 Turbo"}, "status": "success", "duration_ms": 65})
        await call(c, "complete_trace", {"trace_id": trace["id"], "outcome": "Enriched graph with latest AI industry developments from web search", "success": True})
        print(f"  + Reasoning trace: {trace['id'][:8]}... (3 steps, 3 tool calls)")

        # ==================================================================
        # FORGE CONVERSATION — Go data pipeline enrichment
        # ==================================================================
        print("\n--- Forge (Go/Custom HTTP) conversation ---")
        sid = "forge-enrich-001"
        for role, content in [
            ("assistant", "Starting data pipeline enrichment for AI industry entities."),
            ("assistant", "Enriched Sam Altman with properties: ROLE=CEO, COMPANY=OpenAI, EDUCATION=Stanford dropout"),
            ("assistant", "Enriched NVIDIA with properties: MARKET_CAP=$3.4T, FOUNDED=1993, HEADQUARTERS=Santa Clara"),
            ("assistant", "Enriched Anthropic with properties: FUNDING=$7.3B, FOUNDED=2021, HEADQUARTERS=San Francisco"),
            ("assistant", "Pipeline complete. Enriched 3 entities with 9 structured properties."),
        ]:
            await call(c, "add_message", {"session_id": sid, "role": role, "content": content})
        print(f"  + 5 messages in {sid}")

        # Forge reasoning traces (two enrichment pipelines)
        trace = await call(c, "start_trace", {"session_id": sid, "task": "Enrich entity: Sam Altman"})
        s1 = await call(c, "add_step", {"trace_id": trace["id"], "thought": "Looking up Sam Altman in knowledge graph", "action": "get_entity_by_name"})
        await call(c, "record_tool_call", {"step_id": s1["id"], "tool_name": "get_entity_by_name", "arguments": {"name": "Sam Altman"}, "status": "success", "duration_ms": 35})
        s2 = await call(c, "add_step", {"trace_id": trace["id"], "thought": "Adding structured properties from data pipeline", "action": "enrich_entity"})
        await call(c, "record_tool_call", {"step_id": s2["id"], "tool_name": "add_fact", "arguments": {"subject": "Sam Altman", "predicate": "EDUCATION", "obj": "Stanford University (dropped out)"}, "status": "success", "duration_ms": 42})
        await call(c, "record_tool_call", {"step_id": s2["id"], "tool_name": "add_fact", "arguments": {"subject": "Sam Altman", "predicate": "PREVIOUSLY_CEO_OF", "obj": "Y Combinator"}, "status": "success", "duration_ms": 38})
        await call(c, "complete_trace", {"trace_id": trace["id"], "outcome": "Added 3 structured properties to Sam Altman", "success": True})
        print(f"  + Reasoning trace: {trace['id'][:8]}... (2 steps, 3 tool calls)")

        trace = await call(c, "start_trace", {"session_id": sid, "task": "Enrich entity: NVIDIA"})
        s1 = await call(c, "add_step", {"trace_id": trace["id"], "thought": "Running data pipeline for NVIDIA", "action": "enrich_entity"})
        await call(c, "record_tool_call", {"step_id": s1["id"], "tool_name": "add_fact", "arguments": {"subject": "NVIDIA", "predicate": "MARKET_CAP", "obj": "$3.4 trillion"}, "status": "success", "duration_ms": 41})
        await call(c, "record_tool_call", {"step_id": s1["id"], "tool_name": "add_fact", "arguments": {"subject": "NVIDIA", "predicate": "HEADQUARTERS", "obj": "Santa Clara, California"}, "status": "success", "duration_ms": 39})
        await call(c, "complete_trace", {"trace_id": trace["id"], "outcome": "Added 3 structured properties to NVIDIA", "success": True})
        print(f"  + Reasoning trace: {trace['id'][:8]}... (1 step, 2 tool calls)")

        # ==================================================================
        # ATLAS CONVERSATION — Cross-agent synthesis
        # ==================================================================
        print("\n--- Atlas (Python/LangGraph) conversation ---")
        sid = "atlas-synth-001"
        for role, content in [
            ("user", "Synthesize everything we know about the AI industry landscape from all agents' research."),
            ("assistant", "Gathering data from all agents...\n\nLenny (Python/PydanticAI) extracted 18 entities and 25 facts from the Lex Fridman podcast with Sam Altman.\n\nScout (TypeScript/Vercel AI SDK) enriched the graph with latest web search results about GPT-4 Turbo and Anthropic's Series D funding.\n\nForge (Go/Custom HTTP) added structured properties to 3 key entities via data pipeline.\n\nKey findings:\n- The AI industry has 5 major players: OpenAI, Anthropic, Google DeepMind, Meta AI, and NVIDIA\n- NVIDIA is the critical infrastructure provider with $3.4T market cap\n- There's significant talent movement between companies (Dario & Ilya left OpenAI)\n- Microsoft's $13B investment in OpenAI is the largest AI deal\n- Open-source (Meta's LLaMA) vs closed-source (OpenAI's GPT-4) remains a key tension"),
            ("user", "Which agent contributed the most knowledge?"),
            ("assistant", "Lenny (Python) contributed the most raw entities and facts from podcast analysis. Scout (TypeScript) added the most timely information from web searches. Forge (Go) provided the most structured data enrichment. This demonstrates the power of polyglot agent collaboration — each language and framework has different strengths."),
        ]:
            await call(c, "add_message", {"session_id": sid, "role": role, "content": content})
        print(f"  + 4 messages in {sid}")

        # Atlas reasoning trace
        trace = await call(c, "start_trace", {"session_id": sid, "task": "Synthesize AI industry landscape from all agents"})
        s1 = await call(c, "add_step", {"trace_id": trace["id"], "thought": "Gathering entities from shared knowledge graph", "action": "gather_entities", "observation": "Found 18 entities across Person, Organization, and Event types"})
        await call(c, "record_tool_call", {"step_id": s1["id"], "tool_name": "search_entities", "arguments": {"query": "AI", "limit": 50}, "status": "success", "duration_ms": 180})
        s2 = await call(c, "add_step", {"trace_id": trace["id"], "thought": "Collecting reasoning traces from all agents", "action": "gather_traces", "observation": "Found 4 traces: lenny(1), scout(1), forge(2)"})
        await call(c, "record_tool_call", {"step_id": s2["id"], "tool_name": "list_traces", "arguments": {"limit": 50}, "status": "success", "duration_ms": 95})
        s3 = await call(c, "add_step", {"trace_id": trace["id"], "thought": "Synthesizing cross-agent knowledge into unified report", "action": "synthesize", "observation": "Generated comprehensive AI industry landscape report"})
        await call(c, "record_tool_call", {"step_id": s3["id"], "tool_name": "generate_synthesis", "arguments": {"entity_count": 18, "trace_count": 4, "fact_count": 25}, "status": "success", "duration_ms": 320})
        await call(c, "complete_trace", {"trace_id": trace["id"], "outcome": "Synthesized AI industry landscape from 4 agents, 18 entities, 25+ facts", "success": True})
        print(f"  + Reasoning trace: {trace['id'][:8]}... (3 steps, 3 tool calls)")

        # ==================================================================
        # Additional enrichment facts (from various agents)
        # ==================================================================
        print("\n--- Additional enrichment facts ---")
        extra_facts = [
            ("OpenAI", "LAUNCHED", "GPT-4 Turbo"),
            ("OpenAI", "PARTNERED_WITH", "Apple"),
            ("Anthropic", "RAISED", "$2B Series D"),
            ("Anthropic", "BACKED_BY", "Google"),
            ("Sam Altman", "PREVIOUSLY_CEO_OF", "Y Combinator"),
            ("Sam Altman", "EDUCATION", "Stanford University (dropped out)"),
            ("NVIDIA", "MARKET_CAP", "$3.4 trillion"),
            ("NVIDIA", "HEADQUARTERS", "Santa Clara, California"),
            ("NVIDIA", "FOUNDED", "1993"),
            ("Anthropic", "HEADQUARTERS", "San Francisco"),
            ("Anthropic", "FOUNDED", "2021"),
        ]
        for subj, pred, obj in extra_facts:
            await call(c, "add_fact", {"subject": subj, "predicate": pred, "obj": obj})
            print(f"  + {subj} --[{pred}]--> {obj}")

        # ==================================================================
        # Summary
        # ==================================================================
        print("\n" + "=" * 50)
        print("SEED DATA COMPLETE")
        print("=" * 50)
        print(f"  Entities:       {len(people) + len(orgs) + len(events)}")
        print(f"  Facts:          {len(facts) + len(extra_facts)}")
        print("  Conversations:  4 (lenny, scout, forge, atlas)")
        print("  Messages:       21")
        print("  Traces:         5")
        print("  Tool Calls:     14")
        print("  Total nodes:    ~70+")
        print("\nDashboard: http://localhost:3000")


if __name__ == "__main__":
    asyncio.run(seed())
