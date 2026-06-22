import requests

session = requests.Session()
res = session.post('http://localhost:8080/login', data={'username': 'test1@afklive.in', 'password': 'password'})
print("Login status:", res.status_code)

res2 = session.get('http://localhost:8080/api/stream-library')
print("Library GET status:", res2.status_code)
print(res2.text)
