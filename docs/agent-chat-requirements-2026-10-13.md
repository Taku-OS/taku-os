# Phone Agent and conversation UI: user requirements record

Recorded: 2026-10-13. Status: requirements recorded, engineering implementation in progress. The user subsequently canceled synchronization to other Codex conversations; this file is kept only in the main project.

## Consolidated requirements

- Continue developing the Taku wrapper layer on the Android image foundation, with the basic Agent calling the Codex CLI embedded in the image.
- Provide a Chat interface within the phone system, supporting ongoing conversations and task execution.
- The Agent can execute low-level Android commands: set wallpaper, adjust volume, and open apps; use Computer Use and plugins to operate Android apps when needed.
- Computer Use targets the Android phone and its independent background displays, not the user's computer.
- The basic Agent can use plugins to extend its capabilities, with a plugin and community ecosystem to follow.
- Align with the layering, wrapping conventions, and existing Taku design discussed in the “Android image * documentation” conversation; module boundaries, interfaces, and documentation should support future open-source maintenance.
- Addition: provide an independent Agent module entry on the emulator desktop. Beyond chat, it should expose settings, context, temperature, and other information. Implementation distinguishes device battery temperature from model sampling temperature and honestly labels unsupported parameters.
- The existing TaskBroker is the sole scheduler and task-state source; user login state must not enter generic images or source code.

The first six items and additional module requirements came from the user; distinguishing temperatures is an engineering decision; the last item comes from existing engineering rules. Specific runtime interfaces and implementation plans require engineering validation and are not technical details already selected by the user.

## Original voice transcript (English translation)

The original transcript retained verbal slips and transcription errors. In context, the word transcribed as “drawing” meant “conversation,” and “installation conventions” meant “follow the conventions.” The translation below preserves the requests and conversational hesitations.

> I want to start working on the agent part now. You can look at another Codex drawing [conversation], the one about documenting the Android image. At the time we discussed providing an Android image, then adding a Taku wrapper layer, along with plugins, a community, and those things. So in the second layer, the Taku wrapper around this Android image, we want to package a basic agent that can also connect to plugins and things to enhance its capabilities. For this basic agent, we want a chat interface that calls the Codex CLI packaged in our image, running as the agent for this Taku image. We now need an interface within the phone system where we can have conversations, and the agent can run low-level commands to do things, such as setting my desktop background, adjusting the phone volume, opening an app, then operating an app through computer use or plugins like that, operating the electric—no, not the computer, my Android image, to automate things. That is what I have in mind. Now let's produce this agent wrapper, and this... how do I put it? The interface, yes. You should follow the wrapping conventions mentioned in the documentation in that conversation, along with our design, and look at how best to package it so that it is easier to maintain when open-sourced later.

> You can first record what I just said, that is... write it down. You can send it to that current drawing [conversation].

> So I don't end up saying all this only for you to... if something goes wrong, that whole long passage I just said would be lost. Record it and synchronize it to those two drawings [conversations].

> Continue, continue. No need to synchronize anymore; keep working.

> I think we could have an Agent module. When you tap into it in this emulator, it would have not just conversation functionality, but also things like settings, context, temperature, and so on.
