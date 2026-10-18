#!/usr/bin/env python3
"""Original lyrics and reproducible requests for the S20 duration ladder."""
import json
from pathlib import Path

songs = [
    (30, 4096, "01-window-light", "Window Light", "lo-fi hip hop, 78 BPM, warm Rhodes chords, dusty swung drums, round bass, intimate soulful male vocal, hopeful, gentle tape texture, brief intro, complete verse and hook, clean vocal mix", """[Verse]
Rain on the window, blue on the keys,
Someone left a little light for me.
I changed one line and passed it on,
Now a thousand rooms can greet the dawn.

[Chorus]
Leave the source wide open,
Let the little lights grow.
What we build together
Is a world we get to know."""),
    (60, 8192, "02-pass-the-source", "Pass the Source", "conscious hip hop, 92 BPM, crisp boom bap drums, warm sampled-style jazz piano played originally, deep bass, articulate confident rapper, memorable sung hook, uplifting community anthem, tight arrangement, clear lyrics", """[Verse]
Hand-me-down laptop, a crack in the frame,
Learned how to build before I learned the names.
Found a little project with an open front door,
Fixed one broken button, then I fixed one more.
Someone in Manila made the letters read right,
Someone out in Lagos kept the server alight.
No velvet rope at the edge of the stage,
Just a note that said improve it, turn the page.

[Chorus]
Pass the source, pass the spark,
Build a little daylight in the dark.
Take what you need, give what you can,
Put tomorrow back in everybody's hands.

[Outro]
From a kitchen table to a hundred shores,
This is ours, this is yours."""),
    (120, 8192, "03-no-locked-doors", "No Locked Doors", "melodic punk rock, 164 BPM, driving live drums, crunchy power chords, melodic picked bass, energetic raspy lead vocal, big gang-vocal chorus, hopeful rebellious open source anthem, dynamic bridge, tight ending, clear words", """[Verse]
They sold us keys that never fit,
A shining box we couldn't fix.
The clock ran out, the screen went black,
We found the screws and took it back.
An old machine on borrowed time,
A patched-up board, a stubborn light.
We wrote our names in pencil first,
Then let the next kid make it work.

[Chorus]
No locked doors! Let the daylight in!
Break it, mend it, try again!
No locked doors! Hear the whole street sing!
Nobody owns the songs we bring!

[Verse]
The night shift sends a small repair,
A student makes the buttons clear.
A reader puts the words in reach,
A thousand voices start to teach.
No perfect heroes, no parade,
Just patient hands and things we've made.
If you can see it, you can learn,
If you can change it, take your turn.

[Chorus]
No locked doors! Let the daylight in!
Break it, mend it, try again!
No locked doors! Hear the whole street sing!
Nobody owns the songs we bring!

[Bridge]
Leave a map beside the wire,
Leave a chair beside the fire.
When my little light burns through,
There'll be a brighter one with you.

[Final Chorus]
No locked doors! Let the daylight in!
Every ending starts again!
No locked doors! Hear the whole street sing!
We can build the world we bring!

[Outro]
Take your turn!
Let it learn!
Keep the light on!"""),
    (180, 16384, "04-common-ground", "Common Ground", "lo-fi neo soul, 76 BPM, lush warm Rhodes, soft acoustic guitar, relaxed swung drums, melodic electric bass, intimate female lead vocal with subtle harmonies, reflective verses, hopeful memorable chorus, spacious instrumental passage, warm clear mix", """[Verse]
The kettle sings beside the screen,
The smallest hours are evergreen.
A message from a place I've never been:
Your little fix has let the morning in.
I used to think the world was made
By distant hands behind a shade.
Then someone left the working on the page,
And I could see a doorway in the maze.

[Chorus]
Meet me on the common ground,
Where the quiet hands are building sound.
Take a little, leave a little more,
Keep a light beside the open door.
We don't have to own the sun
To share the warmth with everyone.
Meet me where the walls come down,
Meet me on the common ground.

[Verse]
An aging phone begins to sing,
A garden grows from borrowed things.
The careful notes that someone left behind
Become a bridge for someone else's mind.
Across the sea, another dawn,
Another person carries on.
We may not ever stand in the same room,
But every little kindness leaves its tune.

[Chorus]
Meet me on the common ground,
Where the quiet hands are building sound.
Take a little, leave a little more,
Keep a light beside the open door.
We don't have to own the sun
To share the warmth with everyone.
Meet me where the walls come down,
Meet me on the common ground.

[Instrumental]

[Bridge]
Not every seed will find the rain,
Not every try will ease the pain.
But nothing shared is lost for good,
It waits until it's understood.

[Final Chorus]
Meet me on the common ground,
Let a thousand little lights reach out.
Take a little, leave a little more,
Someone's waiting at the open door.
We don't have to own the sun
To make a brighter day for someone.
When the evening settles down,
Leave your light on common ground.

[Outro]
Leave your light on.
I'll leave mine on.
We'll leave the door open."""),
    (210, 16384, "05-fork-the-future", "Fork the Future", "conscious hip hop, 90 BPM, soulful boom bap, rich live bass, original jazz guitar and piano, charismatic articulate rap vocal, soaring soulful sung chorus, open source revolution theme, evolving arrangement, stripped-back bridge, triumphant final chorus, polished clear vocal mix", """[Intro]
One little change.
One open door.
You know what we're building for.

[Verse]
I had a secondhand tower and a fan that screamed,
A folder full of questions and a half-built dream.
Every road had a toll, every tool had a chain,
Pay to see the answer, pay to ask again.
Then I found a little corner where the plans were clear,
Where a stranger said welcome, you can start right here.
First patch was a typo, second made a light,
Third one kept a neighbor's old machine alive.
Somebody checked my work, showed me what I'd missed,
Didn't need a pedestal, just a patient list.
Now the thing I thought was magic has a shape I understand,
And the future feels different when it's something in your hands.

[Chorus]
Fork the future, share the flame,
A thousand different voices, no two the same.
Build it in the open, let the whole world see,
What we make together is what freedom means to me.
Pass the source, let it grow,
Leave a little daylight everywhere you go.

[Verse]
From the library basement to a village school,
Someone writes a language into someone else's tool.
Someone makes it smaller so an old phone breathes,
Someone makes it readable for eyes that struggle to see.
Someone takes the midnight shift and finds a hidden flaw,
Puts the fix out in the morning where it's there for all.
That is how a little seed becomes a forest canopy,
Different roots beneath the earth, a shared reality.
We can disagree and still improve the same design,
Different roads can meet without erasing either sign.
Credit every shoulder that we stand upon,
Write the map down clearly so the next can carry on.

[Chorus]
Fork the future, share the flame,
A thousand different voices, no two the same.
Build it in the open, let the whole world see,
What we make together is what freedom means to me.
Pass the source, let it grow,
Leave a little daylight everywhere you go.

[Bridge]
No one has to know my footsteps just to let me walk.
No one has to own my words to let the people talk.
Keep the private things protected, keep the knowledge in the light.
Make the tools serve the people, let the people hold the rights.

[Verse]
It isn't just a download, it's a habit of repair,
It's a chair beside the table and a reason to be there.
It's a promise to the next kid with a cracked-up screen,
You can ask a better question, you can build a better thing.
Bring your rough first version, bring your half-formed tune,
There's a place for every learner making room for someone new.
We don't need a single hero to decide where we belong,
When a million little changes are the rhythm of the song.

[Final Chorus]
Fork the future, share the flame,
A thousand different voices, no two the same.
Build it in the open, let the whole world see,
What we make together is what freedom means to me.
Pass the source, let it grow,
Leave a little daylight everywhere you go.

[Outro]
One little change becomes a thousand more.
Leave the light on. Leave an open door."""),
]

if __name__ == "__main__":
    out = Path(__file__).with_name("setlist.json")
    result = []
    for i, (seconds, context, slug, title, style, lyrics) in enumerate(songs):
        result.append({"slug": slug, "title": title, "context": context,
            "request": {"style": style, "lyrics": lyrics, "duration": seconds,
                "cot": "full", "steps": 8, "lm_seed": 2026101801+i,
                "seed": 2026101811+i, "output_format": "mp3", "mp3_bitrate": 192,
                "lm_batch_size": 1, "synth_batch_size": 1, "max_chunk": 750,
                "semantic_sampling": {"min_tokens": seconds*25-1, "max_tokens": seconds*25}}})
    out.write_text(json.dumps(result, indent=2)+"\n")
    print(out)
