import { BrowserRouter } from "react-router-dom";
import SeisLearnerLayout from "./components/SeisLearnerLayout.tsx";
import { ChatSessionsProvider } from "./contexts/ChatSessionsContext.tsx";

function App() {
  return (
    <BrowserRouter>
      <ChatSessionsProvider>
        <SeisLearnerLayout />
      </ChatSessionsProvider>
    </BrowserRouter>
  );
}

export default App;
