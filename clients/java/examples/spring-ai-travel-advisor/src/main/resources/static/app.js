const elements = {
  form: document.querySelector("#chat-form"),
  input: document.querySelector("#message-input"),
  send: document.querySelector("#send-button"),
  messages: document.querySelector("#messages"),
  welcome: document.querySelector("#welcome"),
  title: document.querySelector("#conversation-title"),
  conversations: document.querySelector("#conversation-list"),
  historyCount: document.querySelector("#history-count"),
  wishlist: document.querySelector("#wishlist-list"),
  wishlistCount: document.querySelector("#wishlist-count"),
  wishlistButtonCount: document.querySelector("#wishlist-button-count"),
  sidebar: document.querySelector("#sidebar"),
  scrim: document.querySelector("#sidebar-scrim")
};

let currentConversationId = null;
let sending = false;

elements.form.addEventListener("submit", event => {
  event.preventDefault();
  sendMessage(elements.input.value.trim());
});

elements.input.addEventListener("input", resizeComposer);
elements.input.addEventListener("keydown", event => {
  if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
    event.preventDefault();
    elements.form.requestSubmit();
  }
});

document.querySelector("#new-chat").addEventListener("click", startNewConversation);
document.querySelector("#menu-button").addEventListener("click", openSidebar);
document.querySelector("#close-sidebar").addEventListener("click", closeSidebar);
elements.scrim.addEventListener("click", closeSidebar);
document.querySelector("#wishlist-button").addEventListener("click", () => {
  openSidebar();
  document.querySelector("#wishlist-section").scrollIntoView({ behavior: "smooth", block: "end" });
});
document.querySelectorAll("[data-prompt]").forEach(button => {
  button.addEventListener("click", () => sendMessage(button.dataset.prompt));
});

boot();

async function boot() {
  await Promise.allSettled([loadConversations(), loadWishlist()]);
  elements.input.focus();
}

async function sendMessage(message) {
  if (!message || sending) return;
  sending = true;
  elements.send.disabled = true;
  elements.messages.setAttribute("aria-busy", "true");
  hideWelcome();
  appendMessage("user", message);
  elements.input.value = "";
  resizeComposer();
  const typing = appendTyping();

  try {
    const payload = { message };
    if (currentConversationId) payload.conversationId = currentConversationId;
    const response = await fetch("/api/chat", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
    if (!response.ok) throw new Error(await errorMessage(response));
    const reply = await response.json();
    currentConversationId = reply.conversationId;
    typing.remove();
    appendMessage("assistant", reply.message);
    await Promise.allSettled([loadConversations(), loadWishlist()]);
  } catch (error) {
    typing.remove();
    appendMessage("assistant", error.message || "I couldn't reach the travel desk. Please try again.", true);
  } finally {
    sending = false;
    elements.send.disabled = false;
    elements.messages.setAttribute("aria-busy", "false");
    elements.input.focus();
  }
}

async function loadConversations() {
  const response = await fetch("/api/conversations");
  if (!response.ok) throw new Error(`Conversation history unavailable (${response.status})`);
  const conversations = await response.json();
  elements.historyCount.textContent = conversations.length;
  if (!conversations.length) {
    elements.conversations.replaceChildren(emptyNote("Your conversations will appear here."));
    return;
  }
  elements.conversations.replaceChildren(...conversations.map(conversationButton));
  const current = conversations.find(item => item.conversationId === currentConversationId);
  if (current) elements.title.textContent = current.title;
}

function conversationButton(conversation) {
  const button = document.createElement("button");
  button.type = "button";
  button.className = `conversation-item${conversation.conversationId === currentConversationId ? " active" : ""}`;
  button.append(textElement("strong", conversation.title), textElement("small", conversation.preview));
  button.addEventListener("click", () => openConversation(conversation.conversationId));
  return button;
}

async function openConversation(conversationId) {
  if (sending || conversationId === currentConversationId) {
    closeSidebar();
    return;
  }
  elements.messages.setAttribute("aria-busy", "true");
  try {
    const response = await fetch(`/api/conversations/${encodeURIComponent(conversationId)}`);
    if (!response.ok) throw new Error(await errorMessage(response));
    const conversation = await response.json();
    currentConversationId = conversation.conversationId;
    elements.title.textContent = conversation.title;
    elements.messages.replaceChildren(...conversation.messages.map(message => messageNode(message.role, message.content)));
    await Promise.all([loadConversations(), loadWishlist()]);
    scrollToLatest();
    closeSidebar();
  } catch (error) {
    showTransientError(error.message || "That conversation could not be opened.");
  } finally {
    elements.messages.setAttribute("aria-busy", "false");
  }
}

function startNewConversation() {
  if (sending) return;
  currentConversationId = null;
  elements.title.textContent = "A new Swiss adventure";
  elements.messages.replaceChildren(elements.welcome);
  elements.welcome.hidden = false;
  loadConversations().catch(console.error);
  loadWishlist().catch(console.error);
  closeSidebar();
  elements.input.focus();
}

async function loadWishlist() {
  if (!currentConversationId) {
    renderWishlist([]);
    return;
  }
  const response = await fetch(`/api/wishlist?conversationId=${encodeURIComponent(currentConversationId)}`);
  if (!response.ok) throw new Error(`Wishlist unavailable (${response.status})`);
  renderWishlist(await response.json());
}

function renderWishlist(items) {
  elements.wishlistCount.textContent = items.length;
  elements.wishlistButtonCount.textContent = items.length;
  if (!items.length) {
    elements.wishlist.replaceChildren(emptyNote("Places you save will collect here."));
    return;
  }
  elements.wishlist.replaceChildren(...items.map(wishlistItem));
}

function wishlistItem(item) {
  const row = document.createElement("div");
  row.className = "wishlist-item";
  row.append(textElement("span", wishlistGlyph(item.itemType), "wishlist-icon"));
  const copy = document.createElement("div");
  copy.append(
    textElement("strong", item.name || `${capitalize(item.itemType)} #${item.itemId}`),
    textElement("small", item.detail || item.itemType)
  );
  row.append(copy);
  return row;
}

function appendMessage(role, content, isError = false) {
  const row = messageNode(role, content, isError);
  elements.messages.append(row);
  scrollToLatest();
  return row;
}

function messageNode(role, content, isError = false) {
  const row = document.createElement("article");
  row.className = `message-row ${role}${isError ? " error" : ""}`;
  if (role === "assistant") row.append(textElement("span", "S", "message-avatar"));
  const body = document.createElement("div");
  body.className = "message-content";
  if (role === "assistant" && !isError) {
    renderMarkdown(body, content);
  } else {
    body.textContent = content ?? "";
  }
  row.append(body);
  return row;
}

// Render the small, predictable Markdown subset used by the travel assistant.
// Nodes are constructed with textContent, so model output cannot inject HTML.
function renderMarkdown(container, markdown) {
  const lines = String(markdown ?? "").replace(/\r\n?/g, "\n").split("\n");
  let list = null;

  for (const rawLine of lines) {
    const line = rawLine.trim();
    if (!line) {
      list = null;
      continue;
    }

    const ordered = line.match(/^(\d+)\.\s+(.+)$/);
    const unordered = line.match(/^[-*]\s+(.+)$/);
    if (ordered || unordered) {
      const tag = ordered ? "ol" : "ul";
      if (!list || list.tagName.toLowerCase() !== tag) {
        list = document.createElement(tag);
        container.append(list);
      }
      const item = document.createElement("li");
      appendInlineMarkdown(item, (ordered || unordered)[2] || (ordered || unordered)[1]);
      list.append(item);
      continue;
    }

    if (/^\s{2,}\S/.test(rawLine) && list?.lastElementChild) {
      list.lastElementChild.append(document.createElement("br"));
      appendInlineMarkdown(list.lastElementChild, line);
      continue;
    }

    list = null;
    const heading = line.match(/^#{1,6}\s+(.+)$/);
    const block = document.createElement(heading ? "h3" : "p");
    appendInlineMarkdown(block, heading ? heading[1] : line);
    container.append(block);
  }
}

function appendInlineMarkdown(parent, text) {
  const tokenPattern = /(\*\*[^*]+\*\*|`[^`]+`|\[[^\]]+\]\(https?:\/\/[^)\s]+\))/g;
  let cursor = 0;
  for (const match of text.matchAll(tokenPattern)) {
    parent.append(document.createTextNode(text.slice(cursor, match.index)));
    const token = match[0];
    if (token.startsWith("**")) {
      parent.append(textElement("strong", token.slice(2, -2)));
    } else if (token.startsWith("`")) {
      parent.append(textElement("code", token.slice(1, -1)));
    } else {
      const link = token.match(/^\[([^\]]+)]\((https?:\/\/[^)]+)\)$/);
      const anchor = textElement("a", link[1]);
      anchor.href = link[2];
      anchor.target = "_blank";
      anchor.rel = "noopener noreferrer";
      parent.append(anchor);
    }
    cursor = match.index + token.length;
  }
  parent.append(document.createTextNode(text.slice(cursor)));
}

function appendTyping() {
  const row = document.createElement("article");
  row.className = "message-row assistant";
  row.append(textElement("span", "S", "message-avatar"));
  const dots = document.createElement("div");
  dots.className = "typing";
  dots.setAttribute("aria-label", "Advisor is thinking");
  dots.append(document.createElement("i"), document.createElement("i"), document.createElement("i"));
  row.append(dots);
  elements.messages.append(row);
  scrollToLatest();
  return row;
}

function hideWelcome() {
  if (elements.welcome?.isConnected) elements.welcome.remove();
}

function resizeComposer() {
  elements.input.style.height = "auto";
  elements.input.style.height = `${Math.min(elements.input.scrollHeight, 150)}px`;
}

function scrollToLatest() {
  requestAnimationFrame(() => elements.messages.scrollTo({ top: elements.messages.scrollHeight, behavior: "smooth" }));
}

function openSidebar() {
  elements.sidebar.classList.add("open");
  elements.scrim.classList.add("open");
}

function closeSidebar() {
  elements.sidebar.classList.remove("open");
  elements.scrim.classList.remove("open");
}

function showTransientError(message) {
  appendMessage("assistant", message, true);
}

async function errorMessage(response) {
  const body = await response.json().catch(() => ({}));
  return body.message || body.error || `Request failed (${response.status})`;
}

function emptyNote(message) {
  return textElement("p", message, "sidebar-empty");
}

function textElement(tag, text, className = "") {
  const node = document.createElement(tag);
  if (className) node.className = className;
  node.textContent = text ?? "";
  return node;
}

function wishlistGlyph(type) {
  return { destination: "⌖", hotel: "H", activity: "△" }[type] || "♡";
}

function capitalize(value) {
  return value ? value.charAt(0).toUpperCase() + value.slice(1) : "Item";
}
