import { Navigate, Route, Routes } from 'react-router-dom'
import { AppShell } from './components/AppShell'
import { Loading } from './components/Notice'
import { useSession } from './hooks/useSession'
import { AboutScreen } from './screens/AboutScreen'
import { ActivitiesScreen } from './screens/ActivitiesScreen'
import { ActivityCreateScreen } from './screens/ActivityCreateScreen'
import { EnrollmentsScreen } from './screens/EnrollmentsScreen'
import { LoginScreen } from './screens/LoginScreen'
import { RegisterScreen } from './screens/RegisterScreen'

export function App() {
  const { user, isRestoring } = useSession()
  if (isRestoring) return <div className="page"><Loading what="session" /></div>
  if (!user) return <LoginScreen />
  return (
    <Routes>
      <Route element={<AppShell />}>
        <Route path="/enrollments" element={<EnrollmentsScreen />} />
        <Route path="/activities" element={<ActivitiesScreen />} />
        <Route path="/activities/new" element={<ActivityCreateScreen />} />
        <Route path="/activities/:activityId" element={<RegisterScreen />} />
        <Route path="/about" element={<AboutScreen />} />
        <Route path="*" element={<Navigate to="/enrollments" replace />} />
      </Route>
    </Routes>
  )
}
